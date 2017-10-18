package com.gemini.jobcoin

import scala.util.Random

trait AddressGenerator {
  def apply(): Address
}

object RandomStringAddressGenerator extends AddressGenerator {
  private[this] val AddressLength = 15
  private[this] val rng = new Random()
  private[this] val chars = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray

  override def apply(): Address = Seq.fill[Char](AddressLength) {
    chars(rng.nextInt(chars.length))
  }.mkString("")
}