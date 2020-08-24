package com.nigeleke.cribbage

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import com.nigeleke.cribbage.TestModel._
import com.nigeleke.cribbage.actors.Game
import com.nigeleke.cribbage.actors.Game._
import com.nigeleke.cribbage.model.{ Attributes, Face }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class DiscardingGameSpec
  extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
  with AnyWordSpecLike
  with BeforeAndAfterEach
  with LogCapturing
  with Matchers {

  val gameId = randomId
  val persistenceId = s"attributes|$gameId"

  implicit val implicitTestKit = testKit

  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[Command, Event, State](
      system,
      Game(gameId),
      SerializationSettings.disabled)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  def discardingGame(f: Attributes => Unit) = {
    val commands = Seq(Join(player1Id), Join(player2Id))
    val result = commands.map(eventSourcedTestKit.runCommand(_)).last
    result.state should be(a[Discarding])
    f(result.stateOfType[Discarding].game)
  }

  "A DiscardingGame" should {

    "allow a player to discard cards into the crib" in discardingGame { game =>
      val playerId = game.players.head
      val discards = game.hands(playerId).take(2)

      val result = eventSourcedTestKit.runCommand(DiscardCribCards(playerId, discards))
      result.command should be(DiscardCribCards(playerId, discards))
      result.event should be(CribCardsDiscarded(playerId, discards))
      result.stateOfType[Discarding].game.hands(playerId) should not contain allElementsOf(discards)
      result.stateOfType[Discarding].game.crib should contain allElementsOf (discards)
    }

    "not allow a discard" when {

      "the discard contains cards not owned by the player" in discardingGame { game =>
        val player1Id = game.players.head
        val discards = game.hands(player1Id).take(2)

        val player2Id = game.players.last
        val result = eventSourcedTestKit.runCommand(DiscardCribCards(player2Id, discards))
        result.command should be(DiscardCribCards(player2Id, discards))
        result.events should be(empty)
        result.stateOfType[Discarding].game.hands(player1Id) should contain allElementsOf (discards)
        result.stateOfType[Discarding].game.hands(player2Id) should not contain allElementsOf(discards)
        result.stateOfType[Discarding].game.crib should be(empty)
      }

      "the discard contains too few cards" in discardingGame { game =>
        val playerId = game.players.head
        val discards = game.hands(playerId).take(1)

        val result = eventSourcedTestKit.runCommand(DiscardCribCards(playerId, discards))
        result.command should be(DiscardCribCards(playerId, discards))
        result.events should be(empty)
        result.stateOfType[Discarding].game.hands(playerId) should contain allElementsOf (discards)
        result.stateOfType[Discarding].game.crib should not contain allElementsOf(discards)
      }

      "the discard contains too many cards" in discardingGame { game =>
        val playerId = game.players.head
        val discards = game.hands(playerId).take(3)

        val result = eventSourcedTestKit.runCommand(DiscardCribCards(playerId, discards))
        result.command should be(DiscardCribCards(playerId, discards))
        result.events should be(empty)
        result.stateOfType[Discarding].game.hands(playerId) should contain allElementsOf (discards)
        result.stateOfType[Discarding].game.crib should not contain allElementsOf(discards)
      }

    }

    "start the Lay" when {

      "both Players have discarded" in discardingGame { game =>
        val player1Id = game.players.head
        val discards1 = game.hands(player1Id).take(2)

        val player2Id = game.players.last
        val discards2 = game.hands(player2Id).take(2)

        eventSourcedTestKit.runCommand(DiscardCribCards(player1Id, discards1))

        val result = eventSourcedTestKit.runCommand(DiscardCribCards(player2Id, discards2))
        result.command should be(DiscardCribCards(player2Id, discards2))
        result.events should contain(CribCardsDiscarded(player2Id, discards2))
        result.events.count(_.isInstanceOf[PlayCutRevealed]) should be(1)
        result.events.filter(_.isInstanceOf[PlayCutRevealed]).foreach { reveal =>
          if (reveal.asInstanceOf[PlayCutRevealed].card.face == Face.Jack)
            result.events.count(_.isInstanceOf[PointsScored]) should be(1)
        }
        result.state should be(a[Playing])
      }

    }

  }

}
