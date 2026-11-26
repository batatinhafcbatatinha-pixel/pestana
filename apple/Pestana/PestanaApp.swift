import SwiftUI

@main
struct PestanaApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ThemedRoot()
                .environmentObject(model)
                .environmentObject(model.settings)
                .environmentObject(Images.shared)
        }
        #if os(macOS)
        .defaultSize(width: 1000, height: 760)
        #endif
    }
}

/// Resolves the reading theme and applies it to the whole app.
struct ThemedRoot: View {
    @EnvironmentObject var settings: Settings
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let t = Themes.resolve(settings.theme, systemDark: scheme == .dark, chordColor: settings.chordColor, lyricColor: settings.lyricColor)
        RootView()
            .environment(\.reading, t)
            .tint(t.chord)
            .preferredColorScheme(settings.theme == "auto" ? nil : (t.dark ? .dark : .light))
    }
}

enum Tab: Hashable { case search, library, tuner, metronome, settings }

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t
    @State private var tab: Tab = .search

    var body: some View {
        ZStack(alignment: .bottom) {
            #if os(macOS)
            NavigationSplitView {
                List(selection: Binding(get: { tab }, set: { if let v = $0 { tab = v } })) {
                    Label("Buscar", systemImage: "magnifyingglass").tag(Tab.search)
                    Label("Biblioteca", systemImage: "books.vertical").tag(Tab.library)
                    Label("Afinador", systemImage: "tuningfork").tag(Tab.tuner)
                    Label("Metrônomo", systemImage: "metronome").tag(Tab.metronome)
                    Label("Ajustes", systemImage: "gearshape").tag(Tab.settings)
                }
                .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            } detail: {
                screen(tab).id(tab)
            }
            #else
            TabView(selection: $tab) {
                screen(.search).tabItem { Label("Buscar", systemImage: "magnifyingglass") }.tag(Tab.search)
                screen(.library).tabItem { Label("Biblioteca", systemImage: "books.vertical") }.tag(Tab.library)
                screen(.tuner).tabItem { Label("Afinador", systemImage: "tuningfork") }.tag(Tab.tuner)
                screen(.metronome).tabItem { Label("Metrônomo", systemImage: "metronome") }.tag(Tab.metronome)
            }
            #endif
            if let msg = model.toast {
                Text(msg)
                    .font(.subheadline)
                    .foregroundColor(t.bg)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 12).fill(t.text))
                    .padding(.bottom, 90)
                    .transition(.opacity)
            }
        }
        .background(t.bg.ignoresSafeArea())
    }

    @ViewBuilder
    private func screen(_ tab: Tab) -> some View {
        NavigationStack {
            Group {
                switch tab {
                case .search: SearchView()
                case .library: LibraryView()
                case .tuner: TunerView()
                case .metronome: MetronomeView()
                case .settings: SettingsView()
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .artist(let a): ArtistView(artist: a)
                case .album(let a): AlbumView(album: a)
                case .song(let s, let q): SongView(song: s, queue: q)
                case .playlist(let id, let name): PlaylistView(id: id, name: name)
                case .importSheet(let text): ImportView(initialText: text)
                case .importPlaylist(let link): ImportPlaylistView(initialLink: link)
                case .settings: SettingsView()
                }
            }
        }
    }
}

// MARK: - Shared pieces

/// Photo (artist or album) with a letter/icon placeholder underneath. Pictures
/// come from URLCache, which this app keeps in memory only.
struct Thumb: View {
    var text: String
    var symbol: String? = nil
    var url: String? = nil
    var round = true
    var size: CGFloat = 42
    @Environment(\.reading) private var t

    var body: some View {
        ZStack {
            Rectangle().fill(t.surface2)
            if let symbol {
                Image(systemName: symbol).font(.system(size: size * 0.4)).foregroundColor(t.muted)
            } else {
                Text(String(text.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                    .font(.system(size: size * 0.4, weight: .bold)).foregroundColor(t.chord)
            }
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { img in img.resizable().scaledToFill() } placeholder: { Color.clear }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: round ? size / 2 : (size > 60 ? 16 : 10)))
    }
}

struct SongThumb: View {
    var song: SongRef
    var size: CGFloat = 42
    @EnvironmentObject var images: Images

    var body: some View {
        Thumb(text: song.title, symbol: "music.note", url: images.artist(song.artistSlug), round: false, size: size)
            .onAppear { if !song.isLocal { images.ensureArtist(song.artistSlug) } }
    }
}

struct Row: View {
    var title: String
    var subtitle: String? = nil
    @Environment(\.reading) private var t

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundColor(t.text).lineLimit(1)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle).font(.subheadline).foregroundColor(t.muted).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SongRowLink: View {
    var song: SongRef
    var queue: [SongRef] = []

    var body: some View {
        NavigationLink(value: Route.song(song, queue)) {
            HStack(spacing: 14) {
                SongThumb(song: song)
                Row(title: song.title, subtitle: song.artist)
            }
        }
    }
}

struct Pill: View {
    var text: String
    var selected: Bool
    var action: () -> Void
    @Environment(\.reading) private var t

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .foregroundColor(selected ? t.bg : t.text)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Capsule().fill(selected ? t.text : t.surface))
        }
        .buttonStyle(.plain)
    }
}

struct EmptyStateView: View {
    var title: String
    var message: String
    @Environment(\.reading) private var t

    var body: some View {
        VStack(spacing: 6) {
            Text(title).font(.headline).foregroundColor(t.text)
            Text(message).font(.subheadline).foregroundColor(t.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40).padding(.horizontal, 24)
    }
}

struct FavButton: View {
    var on: Bool
    var action: () -> Void
    @Environment(\.reading) private var t

    var body: some View {
        Button(action: action) {
            Image(systemName: on ? "heart.fill" : "heart").foregroundColor(on ? t.chord : t.muted)
        }
        .help(on ? "Remover dos favoritos" : "Favoritar")
    }
}

/// List styling shared by the screens: theme background, no separators fuss.
extension View {
    func themedList(_ t: ReadingTheme) -> some View {
        self.listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(t.bg)
    }

    func rowBackground(_ t: ReadingTheme) -> some View {
        self.listRowBackground(t.bg)
    }
}
