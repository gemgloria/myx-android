import SwiftUI
import CoreText
import UIKit

enum KaiFont {
    static let name: String = {
        let postScriptName = "LXGWWenKai-Regular"
        if UIFont(name: postScriptName, size: 17) == nil,
           let url = Bundle.main.url(forResource: "LXGWWenKai-Regular", withExtension: "ttf")
            ?? Bundle.main.url(forResource: "LXGWWenKai-Widget", withExtension: "ttf") {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
        return postScriptName
    }()
    static func system(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
        .custom(name, size: size, relativeTo: .body).weight(weight)
    }
    static var largeTitle: Font { .custom(name, size: 34, relativeTo: .largeTitle) }
    static var title: Font { .custom(name, size: 28, relativeTo: .title) }
    static var title2: Font { .custom(name, size: 22, relativeTo: .title2) }
    static var title3: Font { .custom(name, size: 20, relativeTo: .title3) }
    static var headline: Font { .custom(name, size: 17, relativeTo: .headline).weight(.semibold) }
    static var body: Font { .custom(name, size: 17, relativeTo: .body) }
    static var callout: Font { .custom(name, size: 16, relativeTo: .callout) }
    static var subheadline: Font { .custom(name, size: 15, relativeTo: .subheadline) }
    static var footnote: Font { .custom(name, size: 13, relativeTo: .footnote) }
    static var caption: Font { .custom(name, size: 12, relativeTo: .caption) }
    static var caption2: Font { .custom(name, size: 11, relativeTo: .caption2) }

    static func configureNavigationAppearance() {
        let appearance = UINavigationBarAppearance()
        appearance.configureWithTransparentBackground()
        appearance.titleTextAttributes = [.font: UIFont(name: name, size: 19) ?? UIFont.preferredFont(forTextStyle: .headline)]
        appearance.largeTitleTextAttributes = [.font: UIFont(name: name, size: 34) ?? UIFont.preferredFont(forTextStyle: .largeTitle)]
        UINavigationBar.appearance().standardAppearance = appearance
        UINavigationBar.appearance().scrollEdgeAppearance = appearance
        UINavigationBar.appearance().compactAppearance = appearance
    }
}
