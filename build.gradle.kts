// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

// litertlm 0.18.0 puxa kotlin-stdlib 2.4.0, mas o compilador Kotlin do projeto
// (2.2.x, o mais novo publicado) só lê metadados até 2.3.0. Força o stdlib
// para a versão do compilador; as classes do litertlm são legíveis (só o
// stdlib transitivo estourava).
allprojects {
  configurations.all {
    resolutionStrategy {
      force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.2.10",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10"
      )
    }
  }
}
