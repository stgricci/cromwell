package cromwell.filesystems.drs

import com.typesafe.config.ConfigFactory
import common.assertion.CromwellTimeoutSpec
import cromwell.core.filesystem.CromwellFileSystems
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DrsPathBuilderFactorySpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers{

  behavior of "DrsPathBuilderFactory"

  it should "create a drs filesystem from a config" in {
    val globalFileSystemConfig = ConfigFactory.parseString(
      """|filesystems {
         |  drs {
         |    class = "cromwell.filesystems.drs.DrsPathBuilderFactory"
         |    global {
         |      class = "cromwell.filesystems.drs.DrsFileSystemConfig"
         |      config {
         |        martha {
         |          url = "http://martha-url"
         |        }
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    )

    val fileSystemConfig = ConfigFactory.parseString(
      """|filesystems {
         |  drs {}
         |}
         |""".stripMargin
    )

    val fileSystems = new CromwellFileSystems(globalFileSystemConfig).factoriesFromConfig(fileSystemConfig).right.get
    fileSystems.keys should contain theSameElementsAs List("drs")
    fileSystems("drs") should be(a[DrsPathBuilderFactory])
  }
}
