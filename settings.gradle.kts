rootProject.name = "HMCL-AE"
include(
    "HMCL",
    "HMCLCore",
    "HMCLBoot",
    "HMCLAI"
)

val minecraftLibraries = listOf("HMCLTransformerDiscoveryService", "HMCLMultiMCBootstrap")
include(minecraftLibraries)

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
