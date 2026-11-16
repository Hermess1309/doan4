plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("vn.edu.p2p.desktop.DesktopApplication")
}
