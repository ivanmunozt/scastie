package com.olegych.scastie
package sbt

import remote.{RunPaste, PasteProgress, RunPasteError}
import api.ScalaTargetType

import upickle.default.{read => uread}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.event.LoggingReceive

import scala.concurrent.duration._
import scala.util.control.{NonFatal, NoStackTrace}

import java.nio.file._
import System.{lineSeparator => nl}

class SbtActor() extends Actor with ActorLogging {
  private val sbt = new Sbt()

  def receive = LoggingReceive {
    compilationKiller {
      case paste: RunPaste => {
        import paste.scalaTargetType

        val paste0 =
          if (scalaTargetType == ScalaTargetType.JS) {
            val fastOptJsDest = Paths.get("/tmp", "scastie", paste.id.toString)
            Files.createDirectories(fastOptJsDest)

            paste.copy(
              sbtConfig =
                s"""artifactPath in (Compile, fastOptJS) := file("$fastOptJsDest")""" + nl +
                  paste.sbtConfig
            )
          } else {
            paste.copy(code = instrumentation.Instrument(paste.code))
          }

        def eval(command: String) =
          sbt.eval(command,
                   paste0,
                   processSbtOutput(
                     paste.progressActor,
                     paste.id
                   ))

        applyRunKiller(paste0) {
          if (scalaTargetType == ScalaTargetType.JVM ||
              scalaTargetType == ScalaTargetType.Dotty) {
            eval("run")
          } else if (scalaTargetType == ScalaTargetType.JS) {
            eval("fastOptJs")
          } else if (scalaTargetType == ScalaTargetType.Native) {
            eval("run")
          }
        }
      }
    }
  }

  private def processSbtOutput(progressActor: ActorRef,
                               id: Long): (String, Boolean) => Unit = {
    (line, done) =>
      progressActor ! PasteProgress(
        id = id,
        output = line,
        done = done,
        compilationInfos = extractProblems(line),
        instrumentations = extractInstrumentations(line)
      )
  }

  private def extractProblems(line: String): List[api.Problem] = {
    val sbtProblems =
      try { uread[List[sbtapi.Problem]](line) } catch {
        case NonFatal(e) => List()
      }

    def toApi(p: sbtapi.Problem): api.Problem = {
      val severity = p.severity match {
        case sbtapi.Info    => api.Info
        case sbtapi.Warning => api.Warning
        case sbtapi.Error   => api.Error
      }
      api.Problem(severity, p.line, p.message)
    }

    sbtProblems.map(toApi)
  }

  private def extractInstrumentations(
      line: String): List[api.Instrumentation] = {
    try { uread[List[api.Instrumentation]](line) } catch {
      case NonFatal(e) => List()
    }
  }

  private val compilationKiller = createKiller("CompilationKiller", 2.minutes)
  private val runKiller         = createKiller("RunKiller", 20.seconds)

  private def applyRunKiller(paste: RunPaste)(block: => Unit) {
    runKiller { case _ => block } apply paste
  }

  private def createKiller(
      actorName: String,
      timeout: FiniteDuration): (Actor.Receive) => Actor.Receive = {
    TimeoutActor(actorName, timeout, message => {
      message match {
        case paste: RunPaste =>
          sender ! RunPasteError(paste.id,
                                 s"Killed because of timeout $timeout")
        case _ =>
          log.info("unknown message {}", message)
      }
      preRestart(FatalFailure, Some(message))
    })
  }
}

object FatalFailure extends Throwable with NoStackTrace