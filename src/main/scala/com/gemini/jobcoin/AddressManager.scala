package com.gemini.jobcoin

import com.twitter.util.Future
import scala.collection.mutable

/**
  * This class is effectively an abstraction for a storage service.
  * I've modeled it as a key-value service, because it doesn't really need transaction or key relationships.
  */
trait AddressManager {
  def setUserDropbox(dropbox: Address, userTargetAddresses: Seq[Address]): Future[Unit]

  def getTargetAddresses(address: Address): Future[Seq[Address]]
}

class InMemoryAddressManager extends AddressManager {
  private[this] val m = mutable.Map[Address, Seq[Address]]()

  override def setUserDropbox(dropbox: Address, userTargetAddresses: Seq[Address]): Future[Unit] = {
    m.put(dropbox, userTargetAddresses)
    Future.Done
  }

  override def getTargetAddresses(address: Address): Future[Seq[Address]] = {
    Future.value(m.get(address).getOrElse(Nil))
  }
}