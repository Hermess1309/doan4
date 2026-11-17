plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":relay"))
    implementation("com.formdev:flatlaf:3.5.4")
}

application {
    mainClass.set("vn.edu.p2p.desktop.DesktopApplication")
}
