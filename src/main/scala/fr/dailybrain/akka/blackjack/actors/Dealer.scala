package fr.dailybrain.akka.blackjack.actors

import akka.actor.{Actor, ActorLogging, Props}
import fr.dailybrain.akka.blackjack.actors.Messages._
import fr.dailybrain.akka.blackjack.models._
import akka.pattern.ask
import akka.pattern.pipe
import fr.dailybrain.akka.blackjack.Implicits._
import fr.dailybrain.akka.blackjack.models.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.Seq

object Dealer {
  def props(): Props = Props(new Dealer())
}

case class DealerState(currentHandIndex: Int, nbSplits: Int, hands: Seq[Hand], dealerCards: Seq[PlayingCard]) {
  def canSplit: Boolean = nbSplits < 2
  def currentHand = hands(currentHandIndex)
  def isLastHand = hands.isEmpty || currentHandIndex + 1 == hands.length
  def toSituation: Situation = Situation(currentHand.cards, dealerCards.head, canSplit)
}

class Dealer extends Actor with ActorLogging {

  var isLastRound = false
  //
  val shoeActor = context.actorSelection("/user/shoe")
  val playerActor = context.actorSelection("/user/player")


  def logState(state: DealerState): Unit = {

    val dealerCards = state.dealerCards

    log.debug("")

    log.debug(
      state.hands.foldLeft("") { (acc, _) => acc + "   " } + s"${dealerCards.mkString("|")}|"
    )

    log.debug("")

    log.debug(
      state.hands.foldLeft("") { (acc, hg) => acc + s"${hg.cards.mkString("|")}|    " }
    )

    log.debug("")

    log.debug(
      state.hands.foldLeft("") { (acc, hg) => acc + s"(${hg.cards.kind.value}/${dealerCards.kind.value}) " }
    )

    log.debug("")

  }


  def receive = active(DealerState(0, 0, Seq.empty[Hand], Seq.empty[PlayingCard]))


  def active(state: DealerState): Receive = {

    case Card =>

      val s = sender

      for {
        card <- shoeActor ? Take
      } yield {

        card match {
          case _: PlayingCard => s ! card
          case CutCard =>
            log.debug("CutCard out of the shoe !")
            isLastRound = true
            (shoeActor ? Take) pipeTo s
        }

      }


    case PlaceBet(amount: Double) =>

      log.debug(s"player bet: $amount")

      for {

        playerCardOne <- (self ? Card).mapTo[PlayingCard]
        dealerCard <- (self ? Card).mapTo[PlayingCard]
        playerCardTwo <- (self ? Card).mapTo[PlayingCard]

      } yield {

        val playerCards = Seq(playerCardOne, playerCardTwo)
        val hand = Hand(amount, playerCards)
        val newState = state.copy(currentHandIndex = 0, nbSplits = 0, hands = Seq(hand), dealerCards = Seq(dealerCard))

        logState(newState)

        context become active(newState)

        playerActor ! AskSurrender(newState.toSituation)

      }


    case DoAction(Surrender) =>

      val refund = state.currentHand.bet / 2
      log.debug(s"dealer: refund to player: $refund")
      playerActor ! GiveBet(refund)

      val newHands: Seq[Hand] = state.hands.patch(state.currentHandIndex, Nil, 1)
      val newHandIndex = if(state.currentHandIndex == 0) 0 else state.currentHandIndex - 1
      val newState: DealerState = state.copy(currentHandIndex = newHandIndex, hands = newHands)

      logState(newState)

      context become active(newState)

      self ! NextHand



    case DoAction(NoSurrender) =>

      playerActor ! AskPlay(state.toSituation)


    case DoAction(Stand) =>

      self ! NextHand


    case DoAction(Hit) =>

      for {
        card <- (self ? Card).mapTo[PlayingCard]
      } yield {

        log.debug(s"Dealer took for player: $card")

        val newPlayerCards: Seq[PlayingCard] = state.currentHand.cards :+ card
        val newHand: Hand = state.currentHand.copy(cards = newPlayerCards)
        val newHands: Seq[Hand] = state.hands.patch(state.currentHandIndex, Seq(newHand), 1)
        val newState: DealerState = state.copy(hands = newHands)

        logState(newState)

        context become active(newState)

        newState.currentHand.cards.kind match {
          case h if h.value > 21 =>
            self ! Bust
          case _ =>
            playerActor ! AskPlay(newState.toSituation)
        }

      }


    case DoAction(DoubleDown) =>

      playerActor ! TakeBet(state.currentHand.bet)

      for {
        card <- (self ? Card).mapTo[PlayingCard]
      } yield {

        log.debug(s"Dealer took for player: $card")

        val newPlayerCards: Seq[PlayingCard] = state.currentHand.cards :+ card
        val newHand: Hand = state.currentHand.copy(bet = state.currentHand.bet * 2, cards = newPlayerCards)
        val newHands: Seq[Hand] = state.hands.patch(state.currentHandIndex, Seq(newHand), 1)
        val newState: DealerState = state.copy(hands = newHands)

        logState(newState)

        context become active(newState)

        newState.currentHand.cards.kind match {
          case h if h.value > 21 =>
            self ! Bust
          case _ =>
            self ! NextHand
        }

      }


    case DoAction(Split) =>

      playerActor ! TakeBet(state.currentHand.bet)

      for {

        c3 <- (self ? Card).mapTo[PlayingCard]
        c4 <- (self ? Card).mapTo[PlayingCard]

      } yield {

        log.debug(s"Dealer took for player: $c3| & $c4|")

        val c1 = state.currentHand.cards(0)
        val c2 = state.currentHand.cards(1)

        val hand1 = state.currentHand.copy(splitted = true, cards = Seq(c1, c3))
        val hand2 = state.currentHand.copy(splitted = true, cards = Seq(c2, c4))

        val newHands: Seq[Hand] = state.hands.patch(state.currentHandIndex, Seq(hand1, hand2), 1)
        val newState: DealerState = state.copy(hands = newHands, nbSplits = state.nbSplits + 1)

        logState(newState)

        context become active(newState)

        (c1.rank, c2.rank) match {
          case (Ace, Ace) =>
            //fixme: should move to next next hand
            self ! DealerPlay
          case _ =>
            playerActor ! AskPlay(newState.toSituation)
        }

      }


    case DealerPlay =>

      log.debug("Dealer Play...")

      val allBusted = state.hands.isEmpty
      val onlyBJ = state.hands.forall { h => !h.splitted && h.cards.kind == BlackJack }

      val shouldHit =
        !allBusted &&
          (
            (onlyBJ && state.dealerCards.size == 1 && state.dealerCards.head.value > 9)
            ||
            (!onlyBJ && state.dealerCards.kind.value < 17 )
          )

      if (shouldHit)
        self ! DealerHit
      else
        self ! PayHands


    case DealerHit =>

      for {
        card <- (self ? Card).mapTo[PlayingCard]
      } yield {

        val newState = state.copy(dealerCards = state.dealerCards :+ card)
        logState(newState)

        context become active(newState)

        self ! DealerPlay

      }


    case PayHands =>

      log.debug("Dealer: PayHands !")

      state.hands.foreach { h =>

        (h.cards.kind, state.dealerCards.kind) match {
          case (BlackJack, t) if !h.splitted && t != BlackJack  =>
            playerActor ! GiveBet(h.bet * 2.5)
          case (p, d) if d.value > 21 || p.value > d.value =>
            playerActor ! GiveBet(h.bet * 2)
          case (p, d) if p.value == d.value =>
            playerActor ! GiveBet(h.bet)
          case _ => ()
        }

      }

      if(isLastRound) {
        log.debug("Reshuffle...")
        isLastRound = false
        shoeActor ! Shuffle
      }

      playerActor ! Play


    case NextHand =>

      logState(state)

      if (!state.isLastHand) {

        val newState: DealerState = state.copy(currentHandIndex = state.currentHandIndex + 1)

        context become active(newState)

        playerActor ! AskPlay(newState.toSituation)

      } else {

        self ! DealerPlay

      }


    case Bust =>

      log.debug("Bust !")

      val newHands: Seq[Hand] = state.hands.patch(state.currentHandIndex, Nil, 1)
      val newHandIndex = if(state.currentHandIndex == 0) 0 else state.currentHandIndex - 1
      val newState: DealerState = state.copy(currentHandIndex = newHandIndex, hands = newHands)

      logState(newState)

      context become active(newState)

      if (newState.isLastHand)
        self ! DealerPlay
      else
        self ! NextHand


    case _ =>

      log.error("Unhandled message")


  }

}