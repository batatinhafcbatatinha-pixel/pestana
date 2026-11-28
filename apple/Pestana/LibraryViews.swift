import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var images: Images
    @Environment(\.reading) private var t
    @State private var tab = 0
    @State private var newList = false
    @State private var newName = ""
    /// The playlist whose deletion is being confirmed (held, then "Apagar playlist").
    @State private var deletingList: Playlist?
    @StateObject private var favOrder = SongOrder(key: "favs", firstLabel: "Ordem padrão")

    private let tabs = ["Playlists", "Músicas", "Artistas", "Álbuns", "Minhas cifras"]

    var body: some View {
        let _ = model.version
        List {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs.indices, id: \.self) { i in Pill(text: tabs[i], selected: tab == i) { tab = i } }
                }
            }.rowBackground(t)
            switch tab {
            case 0:
                let lists = model.store.playlists()
                if lists.isEmpty { EmptyStateView(title: "Nenhuma playlist", message: "Crie listas para ensaios, shows ou estudo.").rowBackground(t) }
                ForEach(lists) { p in
                    NavigationLink(value: Route.playlist(p.id, p.name)) {
                        HStack(spacing: 14) {
                            Thumb(text: p.name, symbol: "music.note.list", round: false)
                            Row(title: p.name, subtitle: p.count == 1 ? "1 música" : "\(p.count) músicas")
                        }
                    }
                    // Holding a playlist offers to delete it.
                    .contextMenu {
                        Button(role: .destructive) { deletingList = p } label: { Label("Apagar playlist", systemImage: "trash") }
                    }
                    .rowBackground(t)
                }
            case 1:
                let all = model.store.favs("song").map { SongRef(id: $0.id, title: $0.title, artist: $0.sub) }
                let songs = favOrder.shown(all)
                if all.isEmpty {
                    EmptyStateView(title: "Nenhuma música favorita", message: "Toque no coração ao abrir uma cifra.").rowBackground(t)
                } else {
                    SongOrderBar(order: favOrder, songs: all).rowBackground(t)
                    if songs.isEmpty {
                        EmptyStateView(title: "Nenhuma música com esses filtros", message: "Os filtros olham as cifras salvas no aparelho.").rowBackground(t)
                    }
                }
                ForEach(songs) { SongRowLink(song: $0, queue: songs, subtitle: favOrder.subtitle($0)).rowBackground(t) }
            case 2:
                let artists = model.store.favs("artist")
                if artists.isEmpty { EmptyStateView(title: "Nenhum artista favorito", message: "Favorite artistas pela busca.").rowBackground(t) }
                ForEach(artists, id: \.id) { a in
                    NavigationLink(value: Route.artist(ArtistRef(slug: a.id, name: a.title))) {
                        HStack(spacing: 14) { Thumb(text: a.title, url: images.artist(a.id)); Row(title: a.title) }
                    }.rowBackground(t)
                }
            case 3:
                let albums = model.store.favs("album")
                if albums.isEmpty { EmptyStateView(title: "Nenhum álbum favorito", message: "Favorite álbuns pela busca ou na página do artista.").rowBackground(t) }
                ForEach(albums, id: \.id) { a in
                    let parts = a.id.split(separator: "/").map(String.init)
                    NavigationLink(value: Route.album(AlbumRef(slug: parts.count > 1 ? parts[1] : "", name: a.title, artist: a.sub, artistSlug: parts.first ?? "", year: a.extra))) {
                        HStack(spacing: 14) {
                            Thumb(text: a.title, symbol: "opticaldisc", url: images.album(a.id), round: false)
                            Row(title: a.title, subtitle: [a.sub, a.extra].filter { !$0.isEmpty }.joined(separator: " · "))
                        }
                    }.rowBackground(t)
                }
            default:
                let local = model.store.localSongs()
                if local.isEmpty { EmptyStateView(title: "Nenhuma cifra própria", message: "Importe colando o texto de uma cifra ou um link.").rowBackground(t) }
                ForEach(local) { SongRowLink(song: $0, queue: local).rowBackground(t) }
            }
        }
        .themedList(t)
        .navigationTitle("Biblioteca")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { newName = ""; newList = true } label: { Label("Nova playlist", systemImage: "plus") }
                    NavigationLink(value: Route.importPlaylist("")) { Label("Importar playlist da Cifra Club", systemImage: "music.note.list") }
                    NavigationLink(value: Route.importSheet("")) { Label("Importar cifra", systemImage: "square.and.arrow.down") }
                } label: { Image(systemName: "plus") }
            }
        }
        .alert("Nova playlist", isPresented: $newList) {
            TextField("Nome", text: $newName)
            Button("Criar") {
                let n = newName.trimmingCharacters(in: .whitespaces)
                if !n.isEmpty { model.store.createPlaylist(n); model.changed(); tab = 0 }
            }
            Button("Cancelar", role: .cancel) {}
        }
        .confirmationDialog(
            "Apagar \"\(deletingList?.name ?? "")\"? As cifras continuam salvas.",
            isPresented: Binding(get: { deletingList != nil }, set: { if !$0 { deletingList = nil } }),
            titleVisibility: .visible
        ) {
            Button("Apagar", role: .destructive) {
                if let p = deletingList { model.store.deletePlaylist(p.id); model.changed() }
                deletingList = nil
            }
        }
    }
}

struct PlaylistView: View {
    let id: Int64
    @State var name: String
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t
    @Environment(\.dismiss) private var dismiss
    @State private var songs: [SongRef] = []
    @State private var renaming = false
    @State private var newName = ""
    @State private var deleting = false
    /// Songs picked to add to another playlist or take out (held, then "Selecionar").
    @State private var selecting = false
    @State private var picked = Set<String>()
    @State private var copyingPicked = false
    @State private var removingPicked = false
    @StateObject private var order: SongOrder

    init(id: Int64, name: String) {
        self.id = id
        _name = State(initialValue: name)
        _order = StateObject(wrappedValue: SongOrder(key: "playlist\(id)", firstLabel: "Ordem da lista"))
    }

    var body: some View {
        let shown = order.shown(songs)
        // Songs are moved up and down on the list as it was put together.
        let untouched = order.untouched(songs)
        return List {
            if !songs.isEmpty {
                SongOrderBar(order: order, songs: songs).rowBackground(t)
                if shown.isEmpty {
                    EmptyStateView(title: "Nenhuma música com esses filtros", message: "Os filtros olham as cifras salvas no aparelho.").rowBackground(t)
                }
            } else {
                EmptyStateView(title: "Playlist vazia", message: "Abra uma cifra e use ⋯ › Adicionar à playlist.").rowBackground(t)
            }
            ForEach(shown) { s in
                if selecting {
                    Button { if picked.contains(s.id) { picked.remove(s.id) } else { picked.insert(s.id) } } label: {
                        HStack(spacing: 14) {
                            Image(systemName: picked.contains(s.id) ? "checkmark.circle.fill" : "circle")
                                .font(.title3).foregroundColor(picked.contains(s.id) ? t.chord : t.muted)
                            SongThumb(song: s)
                            Row(title: s.title, subtitle: order.subtitle(s))
                            Spacer()
                        }.contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .rowBackground(t)
                } else {
                    SongRowLink(song: s, queue: shown, subtitle: order.subtitle(s))
                        .contextMenu {
                            Button { picked = [s.id]; selecting = true } label: { Label("Selecionar", systemImage: "checkmark.circle") }
                        }
                        .rowBackground(t)
                }
            }
            .onDelete { idx in
                let gone = idx.map { shown[$0].id }
                for g in gone { model.store.removeFromPlaylist(id, g) }
                songs.removeAll { gone.contains($0.id) }
                model.changed()
            }
            .onMove(perform: untouched ? { from, to in
                songs.move(fromOffsets: from, toOffset: to)
                model.store.reorderPlaylist(id, songs)
            } : nil)
        }
        .themedList(t)
        .navigationTitle(selecting ? (picked.count == 1 ? "1 música selecionada" : "\(picked.count) músicas selecionadas") : name)
        .toolbar {
            if selecting {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { selecting = false; picked = [] }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { copyingPicked = true } label: { Label("Adicionar a outra playlist", systemImage: "text.badge.plus") }
                        .disabled(picked.isEmpty)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(role: .destructive) { removingPicked = true } label: { Label("Remover da playlist", systemImage: "trash") }
                        .disabled(picked.isEmpty)
                }
            } else {
                ToolbarItem(placement: .primaryAction) { playlistMenu }
                #if os(iOS)
                ToolbarItem(placement: .primaryAction) { EditButton() }
                #endif
            }
        }
        .sheet(isPresented: $copyingPicked) {
            AddToPlaylistSheet(songs: songs.filter { picked.contains($0.id) }, exclude: id) { selecting = false; picked = [] }
        }
        .confirmationDialog(
            picked.count == 1 ? "Remover a música selecionada desta playlist?" : "Remover as \(picked.count) músicas selecionadas desta playlist?",
            isPresented: $removingPicked, titleVisibility: .visible
        ) {
            Button("Remover", role: .destructive) {
                for g in picked { model.store.removeFromPlaylist(id, g) }
                songs.removeAll { picked.contains($0.id) }
                selecting = false; picked = []
                model.changed()
            }
        }
        .alert("Renomear", isPresented: $renaming) {
            TextField("Nome", text: $newName)
            Button("Salvar") {
                let n = newName.trimmingCharacters(in: .whitespaces)
                if !n.isEmpty { model.store.renamePlaylist(id, n); name = n; model.changed() }
            }
            Button("Cancelar", role: .cancel) {}
        }
        .confirmationDialog("Apagar \"\(name)\"? As cifras continuam salvas.", isPresented: $deleting, titleVisibility: .visible) {
            Button("Apagar", role: .destructive) { model.store.deletePlaylist(id); model.changed(); dismiss() }
        }
        .onAppear { songs = model.store.playlistSongs(id) }
        .onChange(of: model.version) { _ in songs = model.store.playlistSongs(id) }
    }

    private var playlistMenu: some View {
        Menu {
            Button { picked = []; selecting = true } label: { Label("Selecionar músicas", systemImage: "checkmark.circle") }
            Button { model.downloadForOffline(songs) } label: { Label("Baixar para usar offline", systemImage: "arrow.down.circle") }
            Button { newName = name; renaming = true } label: { Label("Renomear", systemImage: "pencil") }
            Button(role: .destructive) { deleting = true } label: { Label("Apagar playlist", systemImage: "trash") }
        } label: { Image(systemName: "ellipsis.circle") }
    }
}

/// Choose (or create) a playlist and add the songs to it. A song goes into a
/// playlist only once; `exclude` (the playlist they come from) is left out.
struct AddToPlaylistSheet: View {
    let songs: [SongRef]
    var exclude: Int64? = nil
    var onAdded: () -> Void = {}
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    init(song: SongRef) { songs = [song] }

    init(songs: [SongRef], exclude: Int64? = nil, onAdded: @escaping () -> Void = {}) {
        self.songs = songs
        self.exclude = exclude
        self.onAdded = onAdded
    }

    var body: some View {
        let _ = model.version
        let having = model.store.playlistsHaving(songs.map(\.id))
        let wanted = Set(songs.map(\.id)).count
        NavigationStack {
            List {
                Section("Nova playlist") {
                    HStack {
                        TextField("Nome (ex.: Ensaio sábado)", text: $name)
                        Button("Criar") {
                            let n = name.trimmingCharacters(in: .whitespaces)
                            guard !n.isEmpty else { return }
                            add(to: model.store.createPlaylist(n), named: n, having: [])
                        }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                Section("Suas playlists") {
                    ForEach(model.store.playlists().filter { $0.id != exclude }) { p in
                        let has = having[p.id] ?? []
                        let full = has.count >= wanted
                        Button { add(to: p.id, named: p.name, having: has) } label: {
                            HStack {
                                Row(title: p.name, subtitle: full ? (songs.count == 1 ? "Já está nesta playlist" : "Já estão todas nesta playlist")
                                                                  : (p.count == 1 ? "1 música" : "\(p.count) músicas"))
                                if full { Spacer(); Image(systemName: "checkmark").foregroundColor(t.muted) }
                            }
                        }
                        .disabled(full)
                        .opacity(full ? 0.5 : 1)
                    }
                }
            }
            .navigationTitle("Adicionar à playlist")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } } }
        }
        .frame(minWidth: 320, minHeight: 360)
    }

    private func add(to id: Int64, named listName: String, having has: Set<String>) {
        let missing = songs.filter { !has.contains($0.id) }
        missing.forEach { model.store.addToPlaylist(id, $0) }
        model.changed()
        let already = songs.count - missing.count
        if already > 0 {
            model.say("\(missing.count) adicionadas a \(listName) (\(already) já estavam)")
        } else if missing.count == 1 {
            model.say("Adicionada a \(listName)")
        } else {
            model.say("\(missing.count) músicas adicionadas a \(listName)")
        }
        onAdded()
        dismiss()
    }
}

/// Paste a chord sheet, or a link to one, and keep it locally.
struct ImportView: View {
    var initialText: String
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t
    @State private var link = ""
    @State private var title = ""
    @State private var artist = ""
    @State private var key = ""
    @State private var text = ""
    @State private var busy = false
    @State private var error: String?
    @State private var openedSong: SongRef?
    @State private var showSong = false
    @State private var playlistLink: String?
    @State private var showPlaylist = false

    var body: some View {
        Form {
            Section("De um link (Cifra Club, Ultimate Guitar…)") {
                HStack {
                    TextField("https://…", text: $link)
                    Button("Abrir") { fetchLink() }.disabled(busy)
                }
                if busy { ProgressView() }
                if let error { Text(error).foregroundColor(t.chord).font(.footnote) }
                NavigationLink(value: Route.importPlaylist("")) { Text("Importar playlist da Cifra Club") }
            }
            Section("Ou cole o texto") {
                TextField("Título", text: $title)
                TextField("Artista", text: $artist)
                TextField("Tom (opcional)", text: $key)
                TextEditor(text: $text)
                    .font(.system(size: 13, design: .monospaced))
                    .frame(minHeight: 260)
                Button("Salvar e abrir") {
                    let id = model.store.nextLocalId()
                    let s = Sheet(url: "local:\(id)", songId: id, title: title.isEmpty ? "Sem título" : title, artist: artist,
                                  key: key, capo: 0, tuning: "", text: text, versions: [])
                    model.store.saveSheet(s)
                    model.changed()
                    openedSong = SongRef(id: id, title: s.title, artist: s.artist)
                    showSong = true
                }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(t.bg)
        .navigationTitle("Importar cifra")
        .navigationDestination(isPresented: $showSong) {
            if let s = openedSong { SongView(song: s, queue: []) }
        }
        .navigationDestination(isPresented: $showPlaylist) {
            ImportPlaylistView(initialLink: playlistLink ?? "")
        }
        .onAppear { if text.isEmpty { text = initialText } }
    }

    private func fetchLink() {
        let l = link.trimmingCharacters(in: .whitespaces)
        guard l.hasPrefix("http") else { error = "Cole um link começando com http"; return }
        if Source.isPlaylistLink(l) { playlistLink = l; showPlaylist = true; return }
        busy = true
        error = nil
        Task {
            do {
                let ref = SongRef.fromLink(l, title: "", artist: "")
                let s = try await Source.fetchSheet(l, songId: ref.id, title: "", artist: "")
                model.store.saveSheet(s)
                openedSong = SongRef.fromLink(l, title: s.title.isEmpty ? "Sem título" : s.title, artist: s.artist)
                showSong = true
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}

/// Imports Cifra Club playlists: the link of one playlist, or of a profile to pick several.
struct ImportPlaylistView: View {
    var initialLink: String
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t
    @Environment(\.dismiss) private var dismiss
    @State private var link = ""
    @State private var busy = false
    @State private var status: String?
    @State private var page: Source.PlaylistPage?
    @State private var name = ""
    @State private var offline = true
    @State private var picked = Set<String>()

    var body: some View {
        Form {
            Section {
                Text("Cole o link de uma playlist pública, ou do seu perfil para escolher várias. Não precisa entrar na conta.")
                    .font(.footnote).foregroundColor(t.muted)
                HStack {
                    TextField("https://www.cifraclub.com.br/…", text: $link)
                    Button("Ler") { read() }.disabled(busy)
                }
                if busy { ProgressView() }
                if let status { Text(status).font(.footnote).foregroundColor(t.muted) }
            }
            if let p = page {
                Section {
                    Toggle("Baixar cifras para usar offline", isOn: $offline)
                }
                if !p.songs.isEmpty {
                    Section("\(p.songs.count) músicas") {
                        TextField("Nome da playlist", text: $name)
                        ForEach(p.songs) { s in HStack(spacing: 12) { SongThumb(song: s, size: 34); Row(title: s.title, subtitle: s.artist) } }
                    }
                } else {
                    Section("\(p.playlists.count) playlists neste perfil") {
                        ForEach(p.playlists, id: \.url) { pl in
                            Toggle(pl.label, isOn: Binding(
                                get: { picked.contains(pl.url) },
                                set: { on in if on { picked.insert(pl.url) } else { picked.remove(pl.url) } }
                            ))
                        }
                    }
                }
                Section {
                    Button(p.songs.isEmpty ? "Importar \(picked.count) playlists" : "Importar \(p.songs.count) músicas") { importAll() }
                        .disabled(busy || (p.songs.isEmpty && picked.isEmpty))
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(t.bg)
        .navigationTitle("Importar playlist")
        .onAppear {
            if link.isEmpty && !initialLink.isEmpty { link = initialLink; read() }
        }
    }

    private func read() {
        let l = link.trimmingCharacters(in: .whitespaces)
        guard l.hasPrefix("http") else { status = "Cole o link da playlist ou do perfil"; return }
        busy = true
        status = "Lendo a página…"
        page = nil
        Task {
            do {
                let p = try await Source.readPlaylistPage(l)
                page = p
                name = p.title.isEmpty ? "Playlist importada" : p.title
                picked = Set(p.playlists.map(\.url))
                status = nil
            } catch {
                status = error.localizedDescription
            }
            busy = false
        }
    }

    private func importAll() {
        guard let p = page else { return }
        busy = true
        Task {
            var all: [SongRef] = []
            if !p.songs.isEmpty {
                let id = model.store.createPlaylist(name.isEmpty ? "Playlist importada" : name)
                p.songs.forEach { model.store.addToPlaylist(id, $0) }
                all = p.songs
            } else {
                let chosen = p.playlists.filter { picked.contains($0.url) }
                for (i, pl) in chosen.enumerated() {
                    status = "Importando \(pl.label) (\(i + 1)/\(chosen.count))…"
                    if let sub = try? await Source.readPlaylistPage(pl.url), !sub.songs.isEmpty {
                        let id = model.store.createPlaylist(pl.label)
                        sub.songs.forEach { model.store.addToPlaylist(id, $0) }
                        all += sub.songs
                    }
                }
            }
            model.changed()
            model.say("Playlist importada")
            if offline && !all.isEmpty { model.downloadForOffline(all) }
            busy = false
            dismiss()
        }
    }
}
