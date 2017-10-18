package com.gemini.jobcoin

import java.nio.charset.StandardCharsets
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Http, Service, http}
import com.twitter.io.Buf
import com.twitter.util._
import scala.math.BigDecimal.RoundingMode


/*
Important comments and notes:
----------------------------
1. Separation of systems
   Conceptually, I've designed the implementation with the idea that there are a few available network services,
   each of which is in charge of a specific capability.

   The services are as follows:
     - JobcoinNetwork: The implementation of the REST API for jobcoin.gemini.com
     - AddressManager: Basically a storage layer, handles any required persistence related to addresses.
     - Mixer: The core service running the mixing operations. It's the entry point for registering target addresses
       and schedules the transactions after mixing.
     - NetworkMonitor: Implements monitoring of the Jobcoin network. This is the service a mixer can use to monitor
       addresses, and this service notifies watchers in case a monitor triggers.
     - TransactionScheduler: A service for scheduling transactions

   For the simplicity of this project, all of these are running in the memory of the server.
   Though using the same design, they could be independent services.

2. "Network calls":
   In a real micro-services environment, there would be underlying infrastructure that takes care of resiliency
   and performance. The code "assumes" these exist (retries, enqueuing, etc)

   I've made the following assumptions:
   - When making a Future[Unit] call: I assume an enqueueing infrastructure, and only a failure to **enqueue** returns
   an exception.
   - When making a Future[T] call: I assume a direct call to the service. Failure there means the service failed
   handling the request.
   - When implementing a Future[Unit] method: returning a Future.exception assumes to retry the entire method
   - When implementing a Future[T] method: returning a Future.exception assumes to return the exception to the calling service.


3. About open source: I've used mostly Twitter open source code due to my familiarity with it.
   You'll find most of it is self-explanatory.

*/

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
