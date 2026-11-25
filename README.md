# Pestana

Cifras para violão e guitarra, afinador e metrônomo num app Android nativo, leve e sem conta.

- **Rápido e pequeno:** Kotlin + Jetpack Compose, com um núcleo em Rust (via JNI) para o que precisa de velocidade e precisão. O APK release tem cerca de 1,6 MB.
- **Tudo local:** favoritos (músicas, artistas e álbuns), playlists, histórico e ajustes por música ficam num SQLite no aparelho. Não existe conta nem servidor.
- **Offline:** toda cifra aberta fica salva. Dá para baixar uma playlist inteira de uma vez para usar sem internet.

## Leitura de cifras

- Os acordes ficam **sempre sobre a sílaba certa**. Numa tela estreita, a linha de acordes e a letra quebram juntas, na mesma coluna, e nunca no meio de um acorde. As tablaturas quebram em bloco, com as 6 cordas alinhadas.
- **Seções:** Principal, Simplificada, Acordes (sem tablaturas de solo e riff), Solos, Riffs e as outras versões publicadas (Versão 2, 3…). Se a música não tem versão simplificada, o app gera uma, reduzindo os acordes a tríades.
- **Ajustes de leitura:** transposição com a grafia certa (♯/♭ conforme o tom), capotraste com formas equivalentes, simplificar acordes, mostrar ou ocultar tablaturas e diagramas.
- **Diagramas de acorde para qualquer acorde:** a tabela cobre os shapes abertos clássicos, e os demais saem de uma busca que pontua a tocabilidade (posição, pestana, cordas abafadas, baixo correto). Há modo canhoto.
- **Rolagem automática:** velocidade ajustável, lembrada por música.
- **Pedal Bluetooth:** Page Up/Down, setas e espaço viram a página. As teclas de volume também podem rolar (opcional).
- **Temas pensados para leitura:** Escuro, Claro, Sépia (Kindle), Papel (E-ink), Noturno (luz quente), Preto (AMOLED) e Alto contraste.
- A tela fica ligada enquanto a cifra está aberta. Playlists tocam em sequência, com botões de próxima e anterior.

## Afinador

- Usa o **McLeod Pitch Method** (NSDF com interpolação parabólica) em Rust, sobre a entrada de áudio *UNPROCESSED* quando o aparelho suporta (sem redução de ruído nem ganho automático), e um filtro mediano que descarta saltos de oitava. Nos testes com sinal sintético, o erro fica abaixo de 0,5 cent de 31 Hz a 988 Hz.
- **Modos:** automático (cromático), que mostra a nota que você toca, ou por corda (você escolhe a corda ou deixa o app detectar).
- **23 afinações:** violão/guitarra (padrão, meio tom e um tom abaixo, Drop D/C#/C/B, DADGAD, Open G/D/E/A/C, 7 cordas), baixo de 4 e 5 cordas, ukulele, cavaquinho e viola caipira.
- Referência A4 ajustável entre 415 e 466 Hz.

## Metrônomo

Tem precisão de amostra: os cliques são mixados no próprio fluxo de áudio, então o tempo não deriva. Inclui tap tempo, compassos de 2/4 a 12/8, subdivisões (colcheia, tercina e semicolcheia) e acento no primeiro tempo. Também dá para ligar o metrônomo de dentro da cifra.

## De onde vêm as cifras

A busca usa o endpoint público de busca da Cifra Club. O próprio celular abre a página da música como um navegador faria: primeiro tenta HTTP simples e, se não der, usa o WebView do sistema, bloqueando anúncios. O núcleo em Rust extrai o bloco `<pre>`, o tom, o capotraste, a afinação e os links de outras versões.

O parser não depende de classes CSS de um site específico, então funciona com outras páginas de cifra (opção **Importar › link**). Também dá para **colar o texto** de qualquer cifra, ou **compartilhar** um link ou texto de outro app para o Pestana.

**Importar playlists da Cifra Club:** cole o link de uma playlist pública, ou do seu perfil para escolher várias, sem fazer login. O app cria playlists locais e, se você quiser, baixa todas as cifras para usar offline.

As cifras pertencem aos autores e aos sites de origem. O app só as exibe para uso pessoal.

## Estrutura

```
core/      Rust: chord.rs (acordes, transposição), sheet.rs (parser e layout),
           shapes.rs (diagramas), pitch.rs (afinador), html.rs (extração), jni_api.rs
android/   App Kotlin + Compose (sem Room, sem bibliotecas de ícones ou imagens)
```

## Build

Requisitos: JDK 17+, Android SDK e NDK, Rust com `cargo-ndk`.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
cd core && cargo test --release     # testes do núcleo
cd ../android && ./gradlew assembleRelease
```

O GitHub Actions gera os APKs a cada push, e uma tag `v*` publica um release.

## Licença

MIT
