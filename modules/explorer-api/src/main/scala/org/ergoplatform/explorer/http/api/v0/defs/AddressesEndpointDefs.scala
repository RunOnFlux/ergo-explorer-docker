package org.ergoplatform.explorer.http.api.v0.defs

import org.ergoplatform.explorer.http.api.ApiErr
import org.ergoplatform.explorer.http.api.commonDirectives._
import org.ergoplatform.explorer.http.api.models.{Items, Paging}
import org.ergoplatform.explorer.http.api.v0.models.{AddressInfo, BalanceInfo, TransactionInfo}
import org.ergoplatform.explorer.{Address, TokenId}
import sttp.tapir._
import sttp.tapir.json.circe._

object AddressesEndpointDefs {

  private val PathPrefix = "addresses"

  def endpoints: List[Endpoint[_, _, _, _]] =
    getAddressDef :: getTxsByAddressDef :: getAssetHoldersDef :: getBalancesDef :: Nil

  def getAddressDef: Endpoint[(Address, Int), ApiErr, AddressInfo, Nothing] =
    baseEndpointDef
      .in(PathPrefix / path[Address])
      .in(confirmations)
      .out(jsonBody[AddressInfo])

  def getTxsByAddressDef: Endpoint[(Address, Paging), ApiErr, Items[TransactionInfo], Nothing] =
    baseEndpointDef
      .in(PathPrefix / path[Address] / "transactions")
      .in(paging)
      .out(jsonBody[Items[TransactionInfo]])

  def getAssetHoldersDef: Endpoint[(TokenId, Paging), ApiErr, List[Address], Nothing] =
    baseEndpointDef
      .in(PathPrefix / "assetHolders" / path[TokenId])
      .in(paging)
      .out(jsonBody[List[Address]])

  def getBalancesDef: Endpoint[Paging, ApiErr, Items[BalanceInfo], Nothing] =
    baseEndpointDef
      .in(PathPrefix / "balances")
      .in(paging)
      .out(jsonBody[Items[BalanceInfo]])
}
