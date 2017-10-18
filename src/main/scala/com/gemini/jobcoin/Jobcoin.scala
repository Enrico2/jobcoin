package com.gemini.jobcoin

import java.nio.charset.StandardCharsets
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Http, Service, http}
import com.twitter.io.Buf
import com.twitter.io.Buf.Utf8
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

  // Provided 2 tumblers, for fun.
  val tumbler = new UniformDistributionTumbler(0.5, "ThisIsWhereFeesGo", Duration.fromSeconds(10), "BigHouseAddress")
  val tumbler2 = new RandomTumbler("BigHouseAddress", Duration.fromSeconds(12))

  val mixer = new InMemoryMixer(
    MixerConfig("BigHouseAddress", tumbler2),
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

    private[this] def handle(e: Throwable): Response = {
      val response = http.Response(http.Status.InternalServerError)
      response.content = Utf8.apply(e.getMessage)
      response
    }

    def apply(request: Request): Future[Response] = {
      val in = Buf.decodeString(request.content, StandardCharsets.UTF_8)

      val split = in.split(' ')
      val cmd = split.head

      try {
        val response = cmd.toLowerCase() match {
          case "send" => network.createTransaction(new Transaction(Some(split(1)), split(2), BigDecimal(split(3)))).map { _ => okResponse }
          case "test" => sampleScript().map { _ => okResponse }
          case "register" => mixer.subscribe(split.drop(1).toSeq).map { dropbox =>
            val rep = http.Response(http.Status.Ok)
            rep.content = Utf8(s"Transfer your coins to $dropbox and they'll be tumbled!")
            rep
          }
          case "balance" => network.getAddressBalance(split(1)).map { balance =>
            val rep = http.Response(http.Status.Ok)
            rep.content = Utf8(s"The balance of ${split(1)} is $balance jobcoins.")
            rep
          }
          case "reset" => reset().map { _ => okResponse }
        }

        response.handle {
          case e: Throwable => handle(e)
        }

      } catch { case e: Throwable => Future.value(handle(e)) }
    }
  }

  val server = Http.serve(":8084", service)
  log.info("---------------- Jobcoin Server ----------------")
  log.info(s"Started Jobcoin server at localhost:8084. Press ctrl+c to kill it.")
  log.info(s"Monitoring of the Jobcoin network will start when the first register is sent")
  Await.ready(server)
}
