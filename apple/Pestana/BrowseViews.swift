import SwiftUI

struct SearchView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var images: Images
    @Environment(\.reading) private var t
    @State private var query = ""
    @State private var results = SearchResults()
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        let recents = model.version >= 0 ? model.store.recents() : []
        List {
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                if recents.isEmpty {
                    EmptyStateView(title: "Comece buscando uma música",
                                   message: "As cifras que você abrir ficam salvas no aparelho e funcionam sem internet.")
                        .rowBackground(t)
                } else {
                    Section("Abertas recentemente") {
                        ForEach(recents) { SongRowLink(song: $0).rowBackground(t) }
                    }
                }
            } else {
                if let error { EmptyStateView(title: "Ops", message: error).rowBackground(t) }
                if !results.artists.isEmpty {
                    Section("Artistas") {
                        ForEach(results.artists, id: \.slug) { a in
                            NavigationLink(value: Route.artist(a)) {
                                HStack(spacing: 14) {
                                    Thumb(text: a.name, url: a.img.isEmpty ? images.artist(a.slug) : a.img)
                                    Row(title: a.name)
                                }
                            }.rowBackground(t)
                        }
                    }
                }
                if !results.songs.isEmpty {
                    Section("Músicas") {
                        ForEach(results.songs) { SongRowLink(song: $0).rowBackground(t) }
                    }
                }
                if !results.albums.isEmpty {
                    Section("Álbuns") {
                        ForEach(results.albums, id: \.key) { a in
                            NavigationLink(value: Route.album(a)) {
                                HStack(spacing: 14) {
                                    Thumb(text: a.name, symbol: "opticaldisc", url: a.img.isEmpty ? images.album(a.key) : a.img, round: false)
                                    Row(title: a.name, subtitle: [a.artist, a.year].filter { !$0.isEmpty }.joined(separator: " · "))
                                }
                            }.rowBackground(t)
                        }
                    }
                }
                if !loading && error == nil && results.isEmpty {
                    EmptyStateView(title: "Nada encontrado", message: "Tente outro nome, ou importe a cifra colando o texto.").rowBackground(t)
                }
            }
        }
        .themedList(t)
        .overlay(alignment: .top) { if loading { ProgressView().padding(.top, 8) } }
        .searchable(text: $query, prompt: "Música, artista ou álbum")
        .navigationTitle("Pestana")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    NavigationLink(value: Route.importSheet("")) { Label("Importar cifra", systemImage: "square.and.arrow.down") }
                    NavigationLink(value: Route.importPlaylist("")) { Label("Importar playlist", systemImage: "music.note.list") }
                    #if os(iOS)
                    NavigationLink(value: Route.settings) { Label("Ajustes", systemImage: "gearshape") }
                    #endif
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { results = SearchResults(); error = nil; loading = false; return }
            try? await Task.sleep(nanoseconds: 280_000_000)
            if Task.isCancelled { return }
            loading = true
            error = nil
            do { results = try await Source.search(q) } catch { self.error = "Sem conexão com a busca."; results = SearchResults() }
            loading = false
        }
    }
}

/// Big photo + name header.
struct HeaderView: View {
    var title: String
    var subtitle: String
    var url: String?
    var round: Bool
    var symbol: String?
    @Environment(\.reading) private var t

    var body: some View {
        HStack(spacing: 16) {
            Thumb(text: title, symbol: symbol, url: url, round: round, size: 96)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.title2.bold()).foregroundColor(t.text).lineLimit(2)
                if !subtitle.isEmpty { Text(subtitle).foregroundColor(t.muted).lineLimit(1) }
            }
            Spacer()
        }
        .padding(.vertical, 8)
    }
}

struct ArtistView: View {
    let artist: ArtistRef
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var images: Images
    @Environment(\.reading) private var t
    @State private var songs: [SongRef] = []
    @State private var albums: [AlbumRef] = []
    /// The artist's page on the site: the most played songs and the whole discography.
    @State private var page: Source.ArtistPage?
    @State private var loading = true
    @State private var fav = false

    var body: some View {
        let img = artist.img.isEmpty ? images.artist(artist.slug) : artist.img
        let top = page?.top ?? []
        // Every album the site lists; what the search found until that arrives.
        let listed = page?.albums ?? []
        let discs = listed.isEmpty ? albums : listed
        // The search's songs that are not among the most played already.
        let others = songs.filter { s in !top.contains(where: { $0.id == s.id }) }
        return List {
            HeaderView(title: artist.name, subtitle: "Artista", url: img, round: true, symbol: nil).rowBackground(t)
            if loading { ProgressView().frame(maxWidth: .infinity).rowBackground(t) }
            if top.isEmpty && others.isEmpty && !loading {
                EmptyStateView(title: "Sem músicas", message: "Não encontrei cifras desse artista agora.").rowBackground(t)
            }
            if !top.isEmpty {
                Section("Mais tocadas") { ForEach(top) { SongRowLink(song: $0, queue: top).rowBackground(t) } }
            }
            if !discs.isEmpty {
                Section(discs.count == 1 ? "1 álbum" : "\(discs.count) álbuns") {
                    ForEach(discs, id: \.key) { a in
                        NavigationLink(value: Route.album(a)) {
                            HStack(spacing: 14) {
                                Thumb(text: a.name, symbol: "opticaldisc", url: a.img.isEmpty ? images.album(a.key) : a.img, round: false)
                                Row(title: a.name, subtitle: [a.year, a.kind == "single" ? "Single/EP" : ""].filter { !$0.isEmpty }.joined(separator: " · "))
                            }
                        }.rowBackground(t)
                    }
                }
            }
            if !others.isEmpty {
                Section(top.isEmpty ? "Músicas" : "Outras músicas") { ForEach(others) { SongRowLink(song: $0, queue: others).rowBackground(t) } }
            }
        }
        .themedList(t)
        .navigationTitle(artist.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                FavButton(on: fav) { fav = model.toggleFav(artist: artist) }
            }
        }
        .task {
            if let cached = Source.cachedArtistPage(artist.slug) {
                page = cached
            } else {
                page = await Source.artistPage(artist)
            }
        }
        .task {
            fav = model.store.isFav("artist", artist.slug)
            await Source.artistSongs(artist) { s, a in
                if !s.isEmpty { songs = s }
                albums = a
            }
            loading = false
        }
    }
}

struct AlbumView: View {
    let album: AlbumRef
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var images: Images
    @Environment(\.reading) private var t
    @State private var songs: [SongRef] = []
    @State private var loading = true
    @State private var fav = false
    // Inside one album, ordering by artist, album or release says nothing.
    @StateObject private var order = SongOrder(key: "album", firstLabel: "Faixas", sorts: [.manual, .played, .title])

    var body: some View {
        let shown = order.shown(songs)
        return List {
            HeaderView(title: album.name, subtitle: [album.artist, album.year].filter { !$0.isEmpty }.joined(separator: " · "),
                       url: album.img.isEmpty ? images.album(album.key) : album.img, round: false, symbol: "opticaldisc").rowBackground(t)
            if loading { ProgressView().frame(maxWidth: .infinity).rowBackground(t) }
            if !songs.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        NavigationLink(value: Route.song(shown.first ?? songs[0], shown.isEmpty ? songs : shown)) {
                            Text("Tocar em sequência").font(.subheadline).padding(.horizontal, 14).padding(.vertical, 7)
                                .background(Capsule().fill(t.surface)).foregroundColor(t.text)
                        }.buttonStyle(.plain)
                        NavigationLink(value: Route.artist(ArtistRef(slug: album.artistSlug, name: album.artist))) {
                            Text("Artista").font(.subheadline).padding(.horizontal, 14).padding(.vertical, 7)
                                .background(Capsule().fill(t.surface)).foregroundColor(t.text)
                        }.buttonStyle(.plain)
                        Pill(text: "Baixar offline", selected: false) { model.downloadForOffline(songs) }
                    }
                }.rowBackground(t)
                SongOrderBar(order: order, songs: songs).rowBackground(t)
                if shown.isEmpty {
                    EmptyStateView(title: "Nenhuma música com esses filtros", message: "Os filtros olham as cifras salvas no aparelho.").rowBackground(t)
                }
                ForEach(shown) { SongRowLink(song: $0, queue: shown).rowBackground(t) }
            } else if !loading {
                EmptyStateView(title: "Sem músicas", message: "A busca não trouxe as faixas deste álbum.").rowBackground(t)
            }
        }
        .themedList(t)
        .navigationTitle(album.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                FavButton(on: fav) { fav = model.toggleFav(album: album) }
            }
        }
        .task {
            fav = model.store.isFav("album", album.key)
            await Source.albumSongs(album) { songs = $0 }
            loading = false
        }
    }
}
