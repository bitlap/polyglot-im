// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "polyglot_im_flutter_client",
    platforms: [.iOS("13.0")],
    products: [.library(name: "polyglot-im-flutter-client", targets: ["polyglot_im_flutter_client"])],
    dependencies: [.package(name: "FlutterFramework", path: "../FlutterFramework")],
    targets: [.target(name: "polyglot_im_flutter_client", dependencies: [.product(name: "FlutterFramework", package: "FlutterFramework")])]
)
