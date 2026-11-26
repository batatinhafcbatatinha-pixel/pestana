import Foundation

/// Public-domain songs bundled so the reader works on first launch, offline.
enum Samples {
    static let all: [Sheet] = [
        Sheet(url: "local:local/1", songId: "local/1", title: "Amazing Grace", artist: "Tradicional (domínio público)",
              key: "G", capo: 0, tuning: "", text: """
[Intro] G  C/G  G  D7

[Tab - Riff]
e|-----------------|-----------------|
B|-----------------|-----------------|
G|-------0---------|-------0---------|
D|---0h2-----0-----|---0h2---2--0----|
A|-----------------|-----------------|
E|-3---------------|-3---------------|

[Primeira Parte]

G               G7        C        G
  Amazing grace, how sweet the sound
G                 Em        D   D7
  That saved a wretch like me
G             G7         C        G
  I once was lost, but now am found
Em         D7          G
  Was blind, but now I see

[Segunda Parte]

G                G7         C          G
  'Twas grace that taught my heart to fear
G                 Em          D    D7
  And grace my fears relieved
G             G7          C        G
  How precious did that grace appear
Em          D7          G   C/G  G
  The hour I first believed

[Solo]

Parte 1 de 1
e|-------------------------|------------------|
B|-------0---3-1-0---------|------0-----------|
G|---0-2---------------0---|--0-2---2-0-------|
D|-0---------------0-------|------------------|
A|-------------------------|------------------|
E|-------------------------|------------------|

[Final] G  D7  G
""", versions: []),
        Sheet(url: "local:local/2", songId: "local/2", title: "Ciranda, Cirandinha", artist: "Folclore brasileiro",
              key: "C", capo: 0, tuning: "", text: """
[Intro] C  G7  C

[Primeira Parte]

C                          G7
  Ciranda, cirandinha, vamos todos cirandar
                                    C
  Vamos dar a meia volta, volta e meia vamos dar

C                                 G7
  O anel que tu me destes era vidro e se quebrou
                                         C
  O amor que tu me tinhas era pouco e se acabou

[Refrão]

C7                F
  Por isso dona Rosa
G7                C
  Entre dentro desta roda
Am                 Dm
  Diga um verso bem bonito
G7          C
  Diga adeus e vá-se embora
""", versions: []),
    ]
}
