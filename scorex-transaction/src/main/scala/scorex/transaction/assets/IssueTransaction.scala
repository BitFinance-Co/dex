package scorex.transaction.assets

import com.google.common.primitives.Longs
import play.api.libs.json.{JsObject, Json}
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash
import scorex.serialization.Deser
import scorex.transaction.{AssetId, AssetAcc, BalanceChange, Transaction}

import scala.util.Try

case class IssueTransaction(sender: PublicKeyAccount,
                            assetIdOpt: Option[Array[Byte]],
                            name: Array[Byte],
                            description: Array[Byte],
                            quantity: Long,
                            decimals: Byte,
                            reissuable: Boolean,
                            fee: Long,
                            timestamp: Long,
                            signature: Array[Byte]) extends Transaction {

  import IssueTransaction._

  require(description.length <= MaxDescriptionLength)
  require(name.length <= MaxAssetNameLength && name.length >= MinAssetNameLength)
  require(decimals >= 0 && decimals <= MaxDecimals)
  require(fee >= MinFee)
  require(quantity > 0)


  override val assetFee: (Option[AssetId], Long) = (None, fee)
  lazy val assetId = assetIdOpt.getOrElse(id)

  lazy val toSign: Array[Byte] = sender.publicKey ++ assetIdOpt.map(a => (1: Byte) +: a).getOrElse(Array(0: Byte)) ++
    arrayWithSize(name) ++ arrayWithSize(description) ++ Longs.toByteArray(quantity) ++ Array(decimals) ++
    (if (reissuable) Array(1: Byte) else Array(0: Byte)) ++ Longs.toByteArray(fee) ++ Longs.toByteArray(timestamp)

  override val id: Array[Byte] = FastCryptographicHash(toSign)

  override lazy val json: JsObject = Json.obj(
    "id" -> Base58.encode(id),
    "sender" -> sender.address,
    "assetId" -> Base58.encode(assetId),
    "name" -> Base58.encode(name),
    "description" -> Base58.encode(description),
    "quantity" -> quantity,
    "decimals" -> decimals,
    "reissuable" -> reissuable,
    "fee" -> fee,
    "timestamp" -> timestamp,
    "signature" -> Base58.encode(signature)
  )

  override lazy val balanceChanges: Seq[BalanceChange] = Seq(BalanceChange(AssetAcc(sender, Some(assetId)), quantity))

  override def bytes: Array[Byte] = signature ++ toSign
}

object IssueTransaction extends Deser[IssueTransaction] {
  val MaxDescriptionLength = 1000
  val MaxAssetNameLength = 16
  val MinAssetNameLength = 4
  val MinFee = 100000000
  val MaxDecimals = 8

  override def parseBytes(bytes: Array[Byte]): Try[IssueTransaction] = Try {
    import EllipticCurveImpl._
    val signature = bytes.slice(0, SignatureLength)
    val sender = new PublicKeyAccount(bytes.slice(SignatureLength, SignatureLength + KeyLength))
    val assetIsDefined = bytes.slice(SignatureLength + KeyLength, SignatureLength + KeyLength + 1).head == (1: Byte)
    val assetIdOpt = if (!assetIsDefined) None
    else Some(bytes.slice(SignatureLength + KeyLength + 1, 2 * SignatureLength + KeyLength + 1))
    val nameStart = if (!assetIsDefined) SignatureLength + KeyLength + 1 else 2 * SignatureLength + KeyLength + 1
    val (assetName, descriptionStart) = parseArraySize(bytes, nameStart)
    val (description, quantityStart) = parseArraySize(bytes, descriptionStart)
    val quantity = Longs.fromByteArray(bytes.slice(quantityStart, quantityStart + 8))
    val decimals = bytes.slice(quantityStart + 8, quantityStart + 9).head
    val reissuable = bytes.slice(quantityStart + 9, quantityStart + 10).head == (1: Byte)
    val fee = Longs.fromByteArray(bytes.slice(quantityStart + 10, quantityStart + 18))
    val timestamp = Longs.fromByteArray(bytes.slice(quantityStart + 18, quantityStart + 26))
    IssueTransaction(sender, assetIdOpt, assetName, description, quantity, decimals, reissuable, fee, timestamp, signature)
  }

  def create(sender: PrivateKeyAccount,
             assetIdOpt: Option[Array[Byte]],
             name: Array[Byte],
             description: Array[Byte],
             quantity: Long,
             decimals: Byte,
             reissuable: Boolean,
             fee: Long,
             timestamp: Long): IssueTransaction = {
    val unsigned =
      IssueTransaction(sender, assetIdOpt, name, description, quantity, decimals, reissuable, fee, timestamp, null)
    val sig = EllipticCurveImpl.sign(sender, unsigned.toSign)
    unsigned.copy(signature = sig)
  }
}