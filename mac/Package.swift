// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "JLauncherMac",
    platforms: [.macOS(.v14)],
    targets: [
        // Rules shared with the phone: sites, schedules, the lockdown state, unlocking, sync
        .target(name: "LockdownCore", swiftSettings: [.swiftLanguageMode(.v5)]),
        // The menu-bar app
        .executableTarget(name: "JLauncherMac", dependencies: ["LockdownCore"], swiftSettings: [.swiftLanguageMode(.v5)]),
        // Checks for LockdownCore (no XCTest without Xcode): `swift run LockdownChecks`
        .executableTarget(name: "LockdownChecks", dependencies: ["LockdownCore"], swiftSettings: [.swiftLanguageMode(.v5)]),
    ]
)
