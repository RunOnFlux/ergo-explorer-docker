package org.ergoplatform.explorer.migration.migrations

import cats.effect.{IO, Timer}
import cats.instances.list._
import cats.instances.try_._
import cats.syntax.traverse._
import derevo.circe.{decoder, encoder}
import derevo.derive
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.syntax._
import org.ergoplatform.explorer.db.doobieInstances._
import org.ergoplatform.explorer.db.models.{BoxRegister, Output, ScriptConstant}
import org.ergoplatform.explorer.db.repositories.{BoxRegisterRepo, ScriptConstantsRepo}
import org.ergoplatform.explorer.migration.configs.RegistersMigrationConfig
import org.ergoplatform.explorer.migration.migrations.RegistersAndConstantsMigration.LegacyExpandedRegister
import org.ergoplatform.explorer.protocol.models.{ExpandedRegister, RegisterValue}
import org.ergoplatform.explorer.protocol.{sigma, RegistersParser}
import org.ergoplatform.explorer.{HexString, RegisterId, SigmaType}
import tofu.syntax.monadic._

import scala.util.Try

final class RegistersAndConstantsMigration(
  conf: RegistersMigrationConfig,
  registers: BoxRegisterRepo[ConnectionIO],
  constants: ScriptConstantsRepo[ConnectionIO],
  xa: Transactor[IO],
  log: Logger[IO]
)(implicit timer: Timer[IO]) {

  def run: IO[Unit] = updateSchema >> migrateBatch(conf.offset, conf.batchSize)

  def updateSchema: IO[Unit] = {
    val txn =
      recreateRegistersTable >>
      createConstantsTable >>
      alterOutputsTable >>
      alterUOutputsTable
    log.info(s"Updating DB schema ..") >> txn.transact(xa)
  }

  def migrateBatch(offset: Int, limit: Int): IO[Unit] =
    log.info(s"Current offset is [$offset]") *> outputsBatch(offset, limit)
      .transact(xa)
      .flatMap {
        _.traverse { out =>
          expandRegisters(out)
            .flatMap {
              case (_, Nil) =>
                IO.unit
              case (out, regs) =>
                val consts     = extractConstants(out)
                val updatedOut = addErgoTreeTemplateHash(out)
                val txn        = updateOutput(updatedOut) >> registers.insertMany(regs) >> constants.insertMany(consts)
                txn.transact(xa)
            }
            .handleErrorWith(e => log.error(e)("Error while migrating registers"))
        }.flatMap {
          case Nil => IO.unit
          case xs =>
            log.info(s"[${xs.size}] boxes processed") *>
              IO.sleep(conf.interval) >>
              migrateBatch(offset + limit, limit)
        }
      }

  def extractConstants(out: Output): List[ScriptConstant] =
    for {
      constants <- sigma.extractErgoTreeConstants[Try](out.ergoTree).toOption.toList
      (ix, tp, v, rv) <- constants.flatMap { case (ix, c, v) =>
                           sigma.renderEvaluatedValue(c).map { case (tp, rv) => (ix, tp, v, rv) }.toList
                         }
    } yield ScriptConstant(ix, out.boxId, tp, v, rv)

  def addErgoTreeTemplateHash(out: Output): Output = {
    val hash = sigma.deriveErgoTreeTemplateHash[Try](out.ergoTree).get
    out.copy(ergoTreeTemplateHash = hash)
  }

  def expandRegisters(out: Output): IO[(Output, List[BoxRegister])] =
    out.additionalRegisters
      .as[Map[RegisterId, LegacyExpandedRegister]]
      .fold(e => IO.raiseError(new Exception(s"Cannot deserialize registers in: $out, $e")), IO.pure)
      .map { legacyRegisters =>
        val registers = for {
          (id, reg)                       <- legacyRegisters.toList
          RegisterValue(valueType, value) <- RegistersParser[Try].parseAny(reg.rawValue).toOption
        } yield BoxRegister(id, out.boxId, valueType, reg.rawValue, value)
        val registersJson = registers
          .map { case BoxRegister(id, _, valueType, rawValue, decodedValue) =>
            id.entryName -> ExpandedRegister(rawValue, valueType, decodedValue)
          }
          .toMap
          .asJson
        val updatedOutput = out.copy(additionalRegisters = registersJson)
        updatedOutput -> registers
      }

  def outputsBatch(offset: Int, limit: Int): ConnectionIO[List[Output]] =
    sql"""
         |select
         |  o.box_id,
         |  o.tx_id,
         |  o.header_id,
         |  o.value,
         |  o.creation_height,
         |  o.index,
         |  o.ergo_tree,
         |  o.ergo_tree_template_hash,
         |  o.address,
         |  o.additional_registers,
         |  o.timestamp,
         |  o.main_chain
         |from node_outputs o
         |left join box_registers r on o.box_id = r.box_id
         |where r.id is null and o.additional_registers::text != '{}'
         |order by o.creation_height asc
         |offset $offset limit $limit
         """.stripMargin.query[Output].to[List]

  def updateOutput(output: Output): ConnectionIO[Unit] =
    sql"""
         |update node_outputs set additional_registers = ${output.additionalRegisters}
         |where box_id = ${output.boxId}
         """.stripMargin.update.run.void

  def recreateRegistersTable: ConnectionIO[Unit] =
    sql"drop table box_registers".update.run >>
    sql"""
         |create table box_registers
         |(
         |    id               varchar(2)    not null,
         |    box_id           varchar(64)   not null,
         |    value_type       varchar(128)  not null,
         |    serialized_value varchar(4096) not null,
         |    rendered_value   varchar(4096) not null,
         |    primary key (id, box_id)
         |)
         |""".stripMargin.update.run >>
    sql"create index box_registers__id on box_registers (id)".update.run >>
    sql"create index box_registers__box_id on box_registers (box_id)".update.run >>
    sql"create index box_registers__rendered_value on box_registers (rendered_value)".update.run.void

  def createConstantsTable: ConnectionIO[Unit] =
    sql"""
         |create table script_constants
         |(
         |    index            integer       not null,
         |    box_id           varchar(64)   not null,
         |    value_type       varchar(128)  not null,
         |    serialized_value varchar(2048) not null,
         |    rendered_value   varchar(2048) not null,
         |    primary key (index, box_id)
         |)
         |""".stripMargin.update.run >>
    sql"create index script_constants__box_id on script_constants (box_id)".update.run >>
    sql"create index script_constants__rendered_value on script_constants (rendered_value)".update.run.void

  def alterOutputsTable: ConnectionIO[Unit] =
    sql"""
         |alter table node_outputs add column ergo_tree_template_hash varchar(64) not null default '71bc9534d4a4fe8ff67698a5d0f29782836970635de8418da39fee1cd964fcbe'
         |""".stripMargin.update.run >>
    sql"create index node_outputs__ergo_tree_template_hash on node_outputs (ergo_tree_template_hash)".update.run.void

  def alterUOutputsTable: ConnectionIO[Unit] =
    sql"""
         |alter table node_u_outputs add column ergo_tree_template_hash varchar(64) not null default '71bc9534d4a4fe8ff67698a5d0f29782836970635de8418da39fee1cd964fcbe'
         |""".stripMargin.update.run.void >>
    sql"create index node_u_outputs__ergo_tree_template_hash on node_u_outputs (ergo_tree_template_hash)".update.run.void
}

object RegistersAndConstantsMigration {

  @derive(encoder, decoder)
  final case class LegacyExpandedRegister(
    rawValue: HexString
  )

  def apply(
    conf: RegistersMigrationConfig,
    xa: Transactor[IO]
  )(implicit timer: Timer[IO]): IO[Unit] =
    for {
      logger <- Slf4jLogger.create[IO]
      rs     <- BoxRegisterRepo[IO, ConnectionIO]
      sc     <- ScriptConstantsRepo[IO, ConnectionIO]
      _      <- new RegistersAndConstantsMigration(conf, rs, sc, xa, logger).run
    } yield ()
}
