package org.ergoplatform.explorer.http.api.v0.models

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.ergoplatform.explorer.db.models.aggregates.{ExtendedDataInput, ExtendedInput, ExtendedOutput}
import org.ergoplatform.explorer.db.models.{Asset, Transaction}
import org.ergoplatform.explorer.{Id, TxId}
import sttp.tapir.Schema
import sttp.tapir.generic.Derived

final case class TransactionInfo(
  id: TxId,
  headerId: Id,
  timestamp: Long,
  confirmationsCount: Int,
  inputs: List[InputInfo],
  dataInputs: List[DataInputInfo],
  outputs: List[OutputInfo]
)

object TransactionInfo {

  implicit val codec: Codec[TransactionInfo] = deriveCodec

  implicit val schema: Schema[TransactionInfo] =
    implicitly[Derived[Schema[TransactionInfo]]].value
      .modify(_.id)(_.description("Transaction ID"))
      .modify(_.headerId)(_.description("ID of the corresponding header"))
      .modify(_.timestamp)(
        _.description("Timestamp the transaction got into the network")
      )
      .modify(_.confirmationsCount)(_.description("Number of transaction confirmations"))

  def batch(
    txs: List[(Transaction, Int)],
    inputs: List[ExtendedInput],
    dataInputs: List[ExtendedDataInput],
    outputs: List[ExtendedOutput],
    assets: List[Asset]
  ): List[TransactionInfo] = {
    val groupedAssets = assets.groupBy(_.boxId)
    txs.map {
      case (tx, numConfirmations) =>
        val relatedInputs = inputs
          .filter(_.input.txId == tx.id)
          .sortBy(_.input.index)
          .map(InputInfo.apply)
        val relatedDataInputs = dataInputs
          .filter(_.input.txId == tx.id)
          .sortBy(_.input.index)
          .map(DataInputInfo.apply)
        val relatedOutputs = outputs
          .filter(_.output.txId == tx.id)
          .sortBy(_.output.index)
          .map { out =>
            val relAssets = groupedAssets.get(out.output.boxId).toList.flatten
            OutputInfo(out, relAssets)
          }
        apply(
          tx.id,
          tx.headerId,
          tx.timestamp,
          numConfirmations,
          relatedInputs,
          relatedDataInputs,
          relatedOutputs
        )
    }
  }
}
