plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":relay"))
}

application {
    mainClass.set("vn.edu.p2p.cli.CliApplication")
}
