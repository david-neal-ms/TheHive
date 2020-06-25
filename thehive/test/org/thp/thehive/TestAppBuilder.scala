package org.thp.thehive

import java.io.File
import java.nio.file.{Files, Paths}

import javax.inject.{Inject, Provider, Singleton}
import org.apache.commons.io.FileUtils
import org.thp.scalligraph.auth._
import org.thp.scalligraph.models.{Database, Schema}
import org.thp.scalligraph.services.{LocalFileSystemStorageSrv, StorageSrv}
import org.thp.scalligraph.{janus, AppBuilder}
import org.thp.thehive.models.TheHiveSchemaDefinition
import org.thp.thehive.services.notification.notifiers.{AppendToFileProvider, EmailerProvider, NotifierProvider}
import org.thp.thehive.services.notification.triggers._
import org.thp.thehive.services.{LocalKeyAuthProvider, LocalPasswordAuthProvider, LocalUserSrv}

object TestAppBuilderLock

trait TestAppBuilder {

  val databaseName: String = "default"

  def appConfigure: AppBuilder =
    (new AppBuilder)
      .bind[UserSrv, LocalUserSrv]
      .bind[StorageSrv, LocalFileSystemStorageSrv]
      .bind[Schema, TheHiveSchemaDefinition]
      .multiBind[AuthSrvProvider](classOf[LocalPasswordAuthProvider], classOf[LocalKeyAuthProvider], classOf[HeaderAuthProvider])
      .multiBind[NotifierProvider](classOf[AppendToFileProvider])
      .multiBind[NotifierProvider](classOf[EmailerProvider])
      .multiBind[TriggerProvider](classOf[LogInMyTaskProvider])
      .multiBind[TriggerProvider](classOf[CaseCreatedProvider])
      .multiBind[TriggerProvider](classOf[TaskAssignedProvider])
      .multiBind[TriggerProvider](classOf[AlertCreatedProvider])
      .bindToProvider[AuthSrv, MultiAuthSrvProvider]
      .bindActor[DummyActor]("config-actor")
      .bindActor[DummyActor]("notification-actor")
      .bindActor[DummyActor]("integrity-check-actor")
      .addConfiguration("auth.providers = [{name:local},{name:key},{name:header, userHeader:user}]")
      .addConfiguration("play.modules.disabled = [org.thp.scalligraph.ScalligraphModule, org.thp.thehive.TheHiveModule]")
      .addConfiguration("play.mailer.mock = yes")
      .addConfiguration("play.mailer.debug = yes")
      .addConfiguration("storage.localfs.location = /tmp/thp")

  def testApp[A](body: AppBuilder => A): A = {
    TestAppBuilderLock.synchronized {
      if (!Files.exists(Paths.get(s"target/janusgraph-test-database-$databaseName"))) {
        val app = appConfigure
          .addConfiguration(s"""
                               |db {
                               |  provider: janusgraph
                               |  janusgraph {
                               |    storage.backend: berkeleyje
                               |    storage.directory: "target/janusgraph-test-database-$databaseName"
                               |    berkeleyje.freeDisk: 2
                               |  }
                               |}
                               |akka.cluster.jmx.multi-mbeans-in-same-jvm: on
                               |""".stripMargin)
          .bind[Database, janus.JanusDatabase]
          .bindNamedToProvider[Database, BasicDatabaseProvider]("with-thehive-schema")

        app[DatabaseBuilder].build()(app.apply[Database], app[UserSrv].getSystemAuthContext)
      }
    }
    val storageDirectory = Files.createTempDirectory(Paths.get("target"), "janusgraph-test-database").toFile
    FileUtils.copyDirectory(new File(s"target/janusgraph-test-database-$databaseName"), storageDirectory)
    val app = appConfigure
      .bind[Database, janus.JanusDatabase]
      .bindNamedToProvider[Database, BasicDatabaseProvider]("with-thehive-schema")
      .addConfiguration(s"""
                           |db {
                           |  provider: janusgraph
                           |  janusgraph {
                           |    storage.backend: berkeleyje
                           |    storage.directory: $storageDirectory
                           |    berkeleyje.freeDisk: 2
                           |  }
                           |}
                           |""".stripMargin)

    try body(app)
    finally {
      app[Database].close()
      FileUtils.deleteDirectory(storageDirectory)
    }
  }
}

@Singleton
class BasicDatabaseProvider @Inject() (database: Database) extends Provider[Database] {
  override def get(): Database = database
}
