package com.gemini.jobcoin

import com.twitter.util.Future

trait Mixer {
  /**
    * Register the target addresses and receive a dropbox address, where funds transferred to it will get mixed
    * and distributed to the given addresses.
    *
    * @param userTargetAddresses
    * @return
    */
  def subscribe(userTargetAddresses: Seq[Address]): Future[Address]

  /**
    * Notify the mixer of a transaction into a previously watched address.
    *
    * @param transaction
    * @return
    */
  def notifyMixerWatch(transaction: Transaction): Future[Unit]
}

class ProxyMixer(mixer: Future[Mixer]) extends Mixer {
  override def subscribe(userTargetAddresses: Seq[Address]) = mixer.flatMap(_.subscribe(userTargetAddresses))

  override def notifyMixerWatch(transaction: Transaction) = mixer.flatMap(_.notifyMixerWatch(transaction))
}

class InMemoryMixer(
  config: MixerConfig,
  addressGenerator: AddressGenerator,
  addressManager: AddressManager,
  network: JobcoinNetwork,
  networkMonitor: NetworkMonitor,
  taskScheduler: TransactionScheduler
) extends Mixer {
  private[this] val log = Logger.get("Mixer")

  def subscribe(userProvidedAddresses: Seq[Address]): Future[Address] = {
    if (userProvidedAddresses.size == 0) {
      Future.exception(new IllegalArgumentException("No addresses provided. quitting."))
    }

    log.info(s"received subscribe request for addresses ${userProvidedAddresses.mkString(",")}")

    val userTargetAddresses = userProvidedAddresses.distinct
    log.info(s"distinct addresses: $userTargetAddresses")

    val dropbox = addressGenerator()
    log.info(s"generated $dropbox")

    for {
      _ <- addressManager.setUserDropbox(dropbox, userTargetAddresses)
      _ <- networkMonitor.addMixerWatch(dropbox)
    } yield dropbox
  }

  def notifyMixerWatch(transaction: Transaction): Future[Unit] = {
    log.info(s"Mixer notified of transaction $transaction")

    val dropbox = transaction.toAddress

    // Failure to get address will result in retrying.
    // We do this before creating a transaction so as not to create more than one.
    addressManager.getTargetAddresses(dropbox).flatMap { targetAddresses =>
      /*
      Abstractly, this method is called by "dequeuing" (see networks calls comment below).
      we return this future because creating a successful transaction should not happen twice even if other
      dependant operations fail (this is why we use the onSuccess).
      */
      network.createTransaction(new Transaction(dropbox, config.bigHouseAddress, transaction.amount)).onSuccess { _ =>
        val timedTransactions = config.tumbler.apply(targetAddresses, transaction.amount)
        taskScheduler.schedule(timedTransactions)
      }
    }
  }
}
