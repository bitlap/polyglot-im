// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "polyglot_im_flutter_sdk",
    platforms: [.iOS("13.0")],
    products: [.library(name: "polyglot-im-flutter-sdk", targets: ["polyglot_im_flutter_sdk"])],
    dependencies: [.package(name: "FlutterFramework", path: "../FlutterFramework")],
    targets: [.target(name: "polyglot_im_flutter_sdk", dependencies: [.product(name: "FlutterFramework", package: "FlutterFramework")])]
)
