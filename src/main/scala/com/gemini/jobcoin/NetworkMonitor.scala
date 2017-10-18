package com.gemini.jobcoin

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import com.twitter.util._


class NetworkMonitor(
  network: JobcoinNetwork,
  mixer: Mixer
) {
  private[this] val monitoring = new ConcurrentHashMap[Address, Transaction => Future[Unit]]()
  private[this] val lastHandled = new AtomicReference[Time](Time.epoch)
  private[this] val timerTask = new AtomicReference[Option[TimerTask]](None)
  private[this] val MonitorInterval = Duration.fromSeconds(10)

  private[this] val log = Logger.get("NetworkMonitor")

  private[this] def run(): Unit = {
    synchronized {
      val transactions = Await.result(network.getTransactions()).filter(_.timestamp > lastHandled.get())
      log.info(s"received transactions: $transactions")

      // set new last handled transaction. Assuming transactions received are sorted.
      transactions.lastOption.foreach { t => lastHandled.set(t.timestamp) }

      val performActions = transactions.map { transaction =>
        Option(monitoring.get(transaction.toAddress)) match {
          case None => Future.Done
          case Some(runWatch) => runWatch(transaction)
        }
      }

      Await.result(Future.collect(performActions))
    }
  }

  private[this] def activateIfNotActivated(): Unit = {
    timerTask.compareAndSet(
      None,
      Some(Jobcoin.timer.schedule(MonitorInterval) {
        run()
      })
    )
  }

  private[this] def addWatch(address: Address)(handler: Transaction => Future[Unit]): Unit = {
    synchronized {
      monitoring.put(address, handler)
    }
    activateIfNotActivated()
  }

  def addMixerWatch(address: Address): Future[Unit] = {
    log.info(s"adding watch for address $address")
    addWatch(address) { transaction =>
      if (transaction.amount > 0) mixer.notifyMixerWatch(transaction) else Future.Done
    }

    // Returning a Future here because this is a simulated network call between services.
    Future.Done
  }
}
