// swift-tools-version:5.9
// llama.cpp for the orrery triage's iOS floor: the project's own
// prebuilt XCFramework, pinned by build and checksum. Device and
// macOS slices only, which is what the archive and TestFlight need;
// a simulator build of the app will not link this rung.
import PackageDescription

let package = Package(
    name: "LlamaKit",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "llama", targets: ["llama"]),
    ],
    targets: [
        .binaryTarget(
            name: "llama",
            url: "https://github.com/ggml-org/llama.cpp/releases/download/b11026/llama-b11026-xcframework.zip",
            checksum: "264fbf2acd7ad1d7bf565cf4bf5b04ffad18172832304de7360516714cec375c"
        ),
    ]
)
