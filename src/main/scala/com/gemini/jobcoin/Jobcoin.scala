package com.gemini.jobcoin

import java.nio.charset.StandardCharsets
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Http, Service, http}
import com.twitter.io.Buf
import com.twitter.util._
import scala.math.BigDecimal.RoundingMode


object Jobcoin extends App {
  val timer: Timer = new JavaTimer()
  val log = Logger.get("Jobcoin")

  private[this] def sampleScript(): Future[Unit] = {
    val log = Logger.get("SCRIPT")

    val addresses = Seq("RanAcct1", "RanAcct2", "RanAcct3")
    val dropbox = Await.result(mixer.subscribe(addresses))
    log.info(s"Sending 10 Jobcoins to $dropbox")
    network.createTransaction(new Transaction("OGAccount", dropbox, BigDecimal(10).setScale(8)))
  }

  private[this] def reset(): Future[Unit] = {
    val res = network.getTransactions().flatMap { transactions =>
      val addresses = transactions.flatMap { t =>
        Seq(t.fromAddress.getOrElse(""), t.toAddress)
      }.filter {
        case "BigHouseAddress" => false
        case "OGAccount" => false
        case "" => false
        case _ => true
      }.distinct

      log.info(addresses.toString)

      Future.collect(
        addresses.map { address =>
          network.getAddressBalance(address).flatMap { balance =>
            val amount = BigDecimal(balance).setScale(8, RoundingMode.HALF_DOWN)
            if (amount < 0) {
              val transaction = Transaction(Some("OGAccount"), address, amount, Some(balance.substring(1)))
              log.info(s"creating transaction $transaction because $address balance is $balance")
              network.createTransaction(transaction)
            } else if (amount > 0) {
              val transaction = Transaction(Some(address), "OGAccount", amount, Some(balance))
              log.info(s"creating transaction $transaction because $address balance is $balance")
              network.createTransaction(transaction)
            } else Future.Done
          }
        }
      )
    }

    res.unit.onSuccess { _ =>
      log.info("Finished resetting Jobcoin state.")
    }
  }

  // This is used to resolve the circular dependency. (mixer registering with network, network notifying mixer)
  // Because these are abstractly network services, it's reasonable for a 2 services to depend on each other.
  // Especially in an architecture where services dequeue from a distributed queue of jobs any service can enqueue to.
  val mixerPromise = new Promise[Mixer]()
  val proxyMixer = new ProxyMixer(mixerPromise)
  val network = new JobcoinNetwork()

  val tumbler = new UniformDistributionTumbler(0.5, "ThisIsWhereFeesGo", Duration.fromSeconds(10), "BigHouseAddress")
  val tumbler2 = new RandomTumbler("BigHouseAddress", Duration.fromSeconds(12))

  val mixer = new InMemoryMixer(
    MixerConfig("BigHouseAddress", tumbler),
    RandomStringAddressGenerator,
    new InMemoryAddressManager(),
    network,
    new NetworkMonitor(network, proxyMixer),
    new TransactionScheduler(network)
  )

  mixerPromise.setValue(mixer)

  // Implementing a hacky server here.
  // Since this is just for illustration purposes and only used by the CLI.
  val service = new Service[http.Request, http.Response] {
    val okResponse = http.Response(http.Status.Ok)

    def apply(request: Request): Future[Response] = {
      val in = Buf.decodeString(request.content, StandardCharsets.UTF_8)

      val split = in.split(' ')
      val cmd = split.head

      cmd match {
        case "register" => Future.Done.map { _ => okResponse }
        case "send" => Future.Done.map { _ => okResponse }
        case "test" => sampleScript().map { _ => okResponse }
        case "reset" => reset().map { _ => okResponse }
      }
    }
  }

  val server = Http.serve(":8084", service)
  log.info("---------------- Jobcoin Server ----------------")
  log.info(s"Started Jobcoin server at localhost:8084. Press ctrl+c to kill it.")
  log.info(s"Monitoring of the Jobcoin network will start when the first register is sent")
  Await.ready(server)
}
