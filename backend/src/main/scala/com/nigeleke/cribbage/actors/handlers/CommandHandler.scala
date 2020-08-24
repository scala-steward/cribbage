/*
 * Copyright (C) 2020  Nigel Eke
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nigeleke.cribbage.actors.handlers

import akka.event.slf4j.Logger
import akka.persistence.typed.scaladsl.Effect
import com.nigeleke.cribbage.actors.Game._
import com.nigeleke.cribbage.model.{ Card, Cards, Points, Attributes }
import com.nigeleke.cribbage.model.Deck._
import com.nigeleke.cribbage.model.Face
import com.nigeleke.cribbage.model.Player.{ Id => PlayerId }

trait CommandHandler {

  import CommandHandler._

  def handle(): Effect[Event, State] =
    canDo.map(reasons => {
      logger.warn(s"Command not handled because:\n $reasons")
      Effect.unhandled[Event, State]
    }).getOrElse(effects)

  def canDo: Option[String]
  def effects: Effect[Event, State]

}

object CommandHandler {

  lazy val logger = Logger(CommandHandler.getClass.getCanonicalName)

  def scoreCutAtStartOfPlay(attributes: Attributes): Seq[Event] = {
    val deck = attributes.deck.shuffled
    val cut = deck.head // Will always be Some[Card]
    val dealer = attributes.optDealer.get
    val points = if (cut.face == Face.Jack) 2 else 0
    val cutEvent: Event = PlayCutRevealed(cut)
    val scoreEvent: Seq[Event] = if (points != 0) Seq(PointsScored(dealer, points)) else Seq.empty
    val winnerEvent: Seq[Event] = checkWinner(attributes, dealer, points)
    (cutEvent +: (scoreEvent ++ winnerEvent)).toList
  }

  private def checkWinner(attributes: Attributes, playerId: PlayerId, points: Int) = {
    val currentScore = attributes.scores(playerId).front
    if (currentScore + points >= 121) Seq(WinnerDeclared(playerId)) else Seq.empty
  }

  def scoreLay(attributes: Attributes): Seq[Event] = {
    val play = attributes.play
    val currentCards = play.current.map(_.card)

    val fifteensInPlay = {
      val total = currentCards.map(_.value).sum
      if (total == 15) 2 else 0
    }

    val pairsInPlay = {
      val reversed = currentCards.reverse
      val optPlayedCard = reversed.headOption
      (for {
        lastCard <- optPlayedCard
        matchingCards = reversed.drop(1).takeWhile(_.face == lastCard.face)
        size = matchingCards.size
        points = Map(0 -> 0, 1 -> 2, 2 -> 6, 3 -> 12)(size)
      } yield points).getOrElse(0)
    }

    val runsInPlay = {
      val reversed = currentCards.reverse
      val optPlayedCard = reversed.headOption
      val runLengths = reversed.size to 3 by -1
      (for {
        playedCard <- optPlayedCard
        bestRunLength <- runLengths.dropWhile(length => !makesRun(playedCard, reversed.take(length))).headOption
      } yield bestRunLength).getOrElse(0)
    }

    val points = fifteensInPlay + pairsInPlay + runsInPlay
    val scorerId = play.current.last.playerId

    val scoreEvent = if (points != 0) Seq(PointsScored(scorerId, points)) else Seq.empty
    val winnerEvent: Seq[Event] = checkWinner(attributes, scorerId, points)

    scoreEvent ++ winnerEvent
  }

  def endPlay(attributes: Attributes): Seq[Event] = {
    val play = attributes.play
    val playerId = play.current.last.playerId

    val twoFormalPasses = play.passCount == 2
    val allCardsLaid = attributes.hands.forall(_._2.size == 0)

    val playEndedAt31 = play.runningTotal == 31
    val playEndedBelow31 = (twoFormalPasses || allCardsLaid) && !playEndedAt31
    val playEnded = playEndedAt31 || playEndedBelow31

    val points = {
      import scala.language.implicitConversions
      implicit def booleanToInt(b: Boolean): Int = if (b) 1 else 0
      playEndedBelow31 * 1 + playEndedAt31 * 2
    }

    if (playEnded) Seq(PointsScored(playerId, points), PlayCompleted)
    else Seq.empty
  }

  def endPlays(attributes: Attributes): Seq[Event] = {
    val allCardsLaid = attributes.hands.forall(_._2.isEmpty)
    if (allCardsLaid) PlaysCompleted +: scoreHands(attributes.withPlaysReturned())
    else Seq.empty
  }

  def scoreHands(attributes: Attributes): Seq[Event] = {
    val cut = attributes.optCut.get

    def scoreWithWinner(scorerId: PlayerId, points: Int, scoreEvent: Event): Seq[Event] = {
      val winnerEvent =
        if (attributes.scores(scorerId).front + points >= 121) Seq(WinnerDeclared(scorerId))
        else Seq.empty
      scoreEvent +: winnerEvent
    }

    lazy val scorePone = {
      val poneId = attributes.optPone.get
      val points = scoreFor(attributes.hands(poneId), cut)
      scoreWithWinner(poneId, points.total, PoneScored(poneId, points))
    }

    lazy val scoreDealer = {
      val dealerId = attributes.optDealer.get
      val handPoints = scoreFor(attributes.hands(dealerId), cut)
      val cribPoints = scoreFor(attributes.crib, cut)
      scoreWithWinner(dealerId, handPoints.total, DealerScored(dealerId, handPoints)) ++
        scoreWithWinner(dealerId, handPoints.total + cribPoints.total, CribScored(dealerId, cribPoints))
    }

    def scoreFor(cards: Cards, cut: Card): Points = {

      val allCards = cards :+ cut

      val fifteens = {
        val nCards = 2 to 5
        val nFifteens = for {
          n <- nCards
          c <- allCards.combinations(n)
          total = c.map(_.value).sum if total == 15
        } yield ("fifteen: ", c)
        nFifteens.size * 2
      }

      val pairs = {
        val nPairs = for {
          c <- allCards.combinations(2)
          c1 <- c.headOption
          c2 <- c.lastOption
          isPair = c1.face == c2.face if isPair
        } yield ("pair: ", c)
        nPairs.size * 2
      }

      val runs = {
        val nCards = 3 to 5
        val allRuns = (for {
          n <- nCards
          c <- allCards.combinations(n) if isRun(c)
        } yield c).groupBy(_.size)

        val (count, length) =
          if (allRuns.isEmpty) (0, 0)
          else {
            val max = allRuns.keySet.max
            (allRuns(max).size, max)
          }

        count * length
      }

      val heels = (for {
        card <- cards if card.face == Face.Jack && card.suit == cut.suit
      } yield card).length

      val flushes = {
        val allFlush = (cards :+ cut).groupBy(_.suit).size == 1
        val cardsFlush = cards.groupBy(_.suit).size == 1
        if (allFlush) 5
        else if (cardsFlush) 4
        else 0
      }

      Points(pairs = pairs, fifteens = fifteens, runs = runs, flushes = flushes, heels = heels)
    }

    scorePone ++ scoreDealer :+ DealerSwapped
  }

  private def isRun(cards: Cards) = {
    val sorted = cards.sortBy(_.rank)
    val differences = sorted.sliding(2).map { case Seq(x, y, _*) => y.rank - x.rank }
    val differencesNotByOne = differences.filterNot(_ == 1)
    differencesNotByOne.isEmpty
  }

  private def makesRun(playedCard: Card, cards: Cards) = isRun(cards) && cards.contains(playedCard)

}
