import SwiftUI
import UIKit
import Shared

@main
struct RefBoardApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // The shared app handles safe areas and the keyboard itself.
                .ignoresSafeArea(.all)
        }
    }
}

/** Hosts the shared Compose UI. */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(site: "pornpics")
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
