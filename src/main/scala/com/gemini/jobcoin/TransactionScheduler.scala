package com.gemini.jobcoin

import com.twitter.util.{Await, Time}

class TransactionScheduler(network: JobcoinNetwork) {
  private[this] val log = Logger.get("TransactionScheduler")

  // N.B. this could be easily modified to handle any scheduled task.
  // But this exercise only had one type of scheduled task.
  def schedule(transactions: Seq[ScheduledTask[Transaction]]): Unit = {
    log.info(s"scheduling transactions $transactions")
    transactions.foreach { task =>
      Jobcoin.timer.schedule(task.time) {
        log.info(s"running transaction ${task.task}")
        Await.result(network.createTransaction(task.task))
      }
    }
  }
}

case class ScheduledTask[T](time: Time, task: T)