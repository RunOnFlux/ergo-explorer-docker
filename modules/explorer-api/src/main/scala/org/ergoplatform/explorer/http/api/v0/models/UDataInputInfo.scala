package org.ergoplatform.explorer.http.api.v0.models

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.ergoplatform.explorer.db.models.aggregates.ExtendedUDataInput
import org.ergoplatform.explorer.{Address, BoxId, TxId}
import sttp.tapir.Schema
import sttp.tapir.generic.Derived

final case class UDataInputInfo(
  id: BoxId,
  transactionId: TxId,
  value: Option[Long],
  outputTransactionId: Option[TxId],
  address: Option[Address]
)

object UDataInputInfo {

  implicit val codec: Codec[UDataInputInfo] = deriveCodec

  implicit val schema: Schema[UDataInputInfo] =
    implicitly[Derived[Schema[UDataInputInfo]]].value
      .modify(_.id)(_.description("Id of the corresponding box"))
      .modify(_.transactionId)(_.description("ID of the transaction this data input was used in"))
      .modify(_.value)(_.description("Amount of nanoERGs in the corresponding box"))
      .modify(_.outputTransactionId)(_.description("ID of the output transaction"))
      .modify(_.address)(_.description("Address"))

  def apply(in: ExtendedUDataInput): UDataInputInfo =
    new UDataInputInfo(
      in.input.boxId,
      in.input.txId,
      in.value,
      in.outputTxId,
      in.address
    )

  def batch(ins: List[ExtendedUDataInput]): List[UDataInputInfo] =
    ins.sortBy(_.input.index).map(apply)
}
