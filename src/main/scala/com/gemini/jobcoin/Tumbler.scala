package com.gemini.jobcoin

import com.twitter.util.{Duration, Time}
import scala.math.BigDecimal.RoundingMode
import scala.util.Random

trait Tumbler extends ((Seq[Address], BigDecimal) => Seq[ScheduledTask[Transaction]])

/**
  * Distribute funds evenly over the given duration. Includes a fee.
  * @param feePercentage
  * @param feeAccountAddress
  * @param disbursementDuration
  * @param bigHouseAddress
  */
class UniformDistributionTumbler(
  feePercentage: Double,
  feeAccountAddress: Address,
  disbursementDuration: Duration,
  bigHouseAddress: Address
) extends Tumbler {
  require(feePercentage < 100 && feePercentage >= 0, "required: 0 <= feePercentage < 100")

  private[this] val log = Logger.get("UniformDistributionTumbler")

  override def apply(targetAddresses: Seq[Address], amount: BigDecimal): Seq[ScheduledTask[Transaction]] = {
    log.info(s"found target addresses $targetAddresses to receive $amount Jobcoins minus a $feePercentage% fee")

    val n = targetAddresses.size
    val seconds = disbursementDuration.inSeconds / n
    val now = Time.now
    val fee = feePercentage * amount / 100
    val lessFee = amount - fee
    val eachTransferAmount = (lessFee / n).setScale(8, RoundingMode.HALF_DOWN) // num of bitcoin decimal places is 8.

    val remainder = lessFee - (eachTransferAmount * n)

    targetAddresses.zipWithIndex.map { case (address, idx) =>
      val i = idx + 1
      val time = now + Duration.fromSeconds(seconds * i)
      val amountToTransfer = if (i == n) eachTransferAmount + remainder else eachTransferAmount

      ScheduledTask(time, new Transaction(bigHouseAddress, address, amountToTransfer))
    } ++ Seq(ScheduledTask(now, new Transaction(bigHouseAddress, feeAccountAddress, fee)))
  }
}

/**
  * Randomly distribute fund over a total of the given duration. (Random amounts at random intervals)
  *
  * @param bigHouseAddress
  * @param disbursementDuration
  */
class RandomTumbler(
  bigHouseAddress: Address,
  disbursementDuration: Duration
) extends Tumbler {
  private[this] val rng = new Random()

  /*
  The calculation for random amounts/durations is done by generating random values between 0 and max.
  then taking the set of differences between the values, thus resulting in a set whose sum equals max.

  For the duration case, I create a seq durations incremented by the random values.
   */
  override def apply(targetAddresses: Seq[Address], amount: BigDecimal): Seq[ScheduledTask[Transaction]] = {
    val gen = targetAddresses.size - 1
    val now = Time.now
    val sortedAmounts = (Seq(BigDecimal(0), amount) ++ Seq.fill(gen) { amount * rng.nextDouble }).sorted
    val amounts = (sortedAmounts zip sortedAmounts.drop(1)).map { case (a, b) => b - a }

    val totalSeconds = disbursementDuration.inSeconds
    val sortedDurations = (Seq(0, totalSeconds) ++ Seq.fill(gen) { rng.nextInt(totalSeconds) } ).sorted
    val durations =
      (sortedDurations zip sortedDurations.drop(1))
        .map { case (a, b) => b - a }
        .foldLeft(Seq.empty[Int]) { case (sums, dur) => sums ++ Seq(dur + sums.lastOption.getOrElse(0)) }
        .map(Duration.fromSeconds)

    (amounts, durations, targetAddresses).zipped.map { case (amount, duration, address) =>
      ScheduledTask(now + duration, new Transaction(bigHouseAddress, address, amount))
    }.filter(_.task.amount > 0)
  }
}