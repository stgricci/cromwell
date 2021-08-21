import Testing._
import sbt.Keys._
import sbt._
import sbt.io.Path._

import scala.sys.process._

object ContinuousIntegration {
  lazy val ciSettings: Seq[Setting[_]] = List(
    srcCiResources := sourceDirectory.value / "ci" / "resources",
    targetCiResources := target.value / "ci" / "resources",
    vaultToken := userHome / ".vault-token",
    copyCiResources := {
      IO.copyDirectory(srcCiResources.value, targetCiResources.value)
    },
    renderCiResources := {
      minnieKenny.toTask("").value
      copyCiResources.value
      val log = streams.value.log
      val vaultTokenArgs: List[String] = {
        if (sys.env.contains("VAULT_TOKEN")) {
          List("--env", "VAULT_TOKEN")
        } else if (!vaultToken.value.exists()) {
          sys.error(
            s"""The vault token file "${vaultToken.value}" does not exist. Be sure to login using the instructions """ +
              """on https://hub.docker.com/r/broadinstitute/dsde-toolbox/ under "Authenticating to vault"."""
          )
        } else if (vaultToken.value.isDirectory) {
          sys.error(s"""The vault token file "${vaultToken.value}" should not be a directory.""")
        } else {
          List("--volume", s"${vaultToken.value}:/root/.vault-token")
        }
      }
      val cmd = List(
        "docker",
        "run",
        "--rm",
        "--volume", s"${srcCiResources.value}:${srcCiResources.value}",
        "--volume", s"${targetCiResources.value}:${targetCiResources.value}"
      ) ++
        vaultTokenArgs ++
        List(
          "--env", "ENVIRONMENT=not_used",
          "--env", s"INPUT_PATH=${srcCiResources.value}",
          "--env", s"OUT_PATH=${targetCiResources.value}",
          "broadinstitute/dsde-toolbox:dev", "render-templates.sh"
        )
      val result = cmd ! log
      if (result != 0) {
        sys.error(
          "Vault rendering failed. Please double check for errors above and see the setup instructions on " +
            "https://hub.docker.com/r/broadinstitute/dsde-toolbox/"
        )
      }
    }
  )

  def aggregateSettings(rootProject: Project): Seq[Setting[_]] = List(
    // Before compiling, check if the expected projects are aggregated so that they will be compiled-and-tested too.
    Compile / compile := {
      streams.value.log // make sure logger is loaded
      validateAggregatedProjects(rootProject, state.value)
      (Compile / compile).value
    },
  )

  private val copyCiResources: TaskKey[Unit] = taskKey[Unit](s"Copy CI resources.")
  private val renderCiResources: TaskKey[Unit] = taskKey[Unit](s"Render CI resources with Hashicorp Vault.")

  private val srcCiResources: SettingKey[File] = settingKey[File]("Source directory for CI resources")
  private val targetCiResources: SettingKey[File] = settingKey[File]("Target directory for CI resources")
  private val vaultToken: SettingKey[File] = settingKey[File]("File with the vault token")

  /**
    * Get the list of projects defined in build.sbt excluding the passed in root project.
    */
  private def getBuildSbtNames(rootProject: Project, state: State): Set[String] = {
    val extracted = Project.extract(state)
    extracted.structure.units.flatMap({
      case (_, loadedBuildUnit) => loadedBuildUnit.defined.keys
    }).toSet - rootProject.id
  }

  /**
    * Validates that projects are aggregated.
    */
  private def validateAggregatedProjects(rootProject: Project, state: State): Unit = {
    // Get the list of projects explicitly aggregated
    val projectReferences: Seq[ProjectReference] = rootProject.aggregate
    val localProjectReferences = projectReferences collect {
      case localProject: LocalProject => localProject
    }
    val aggregatedNames = localProjectReferences.map(_.project).toSet

    val buildSbtNames = getBuildSbtNames(rootProject, state)
    val missingNames = buildSbtNames.diff(aggregatedNames).toList.sorted
    if (missingNames.nonEmpty) {
      sys.error(s"There are projects defined in build.sbt that are not aggregated: ${missingNames.mkString(", ")}")
    }
  }
}
