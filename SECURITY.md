# Segurança

## Como relatar uma vulnerabilidade

Relate problemas de segurança de forma privada, pelo
[relato privado de vulnerabilidades do GitHub](https://github.com/batatinhafcbatatinha-pixel/pestana/security/advisories/new),
e não em uma issue pública. A resposta vem em até uma semana.

## O que o app faz com seus dados

- Não existe conta, servidor do Pestana, analytics nem anúncios.
- Favoritos, playlists, histórico, ajustes e cifras ficam num banco SQLite
  no próprio aparelho. Nada disso sai dele, a não ser no backup que você
  mesmo exportar.
- O app só conversa com o site das cifras: a busca pública da Cifra Club e
  as páginas das músicas que você abre. Ao abrir uma página pelo WebView, o
  app bloqueia anúncios e rastreadores de terceiros.
- As fotos de artistas e álbuns ficam só na memória e não são gravadas em
  disco.
- O microfone é usado apenas pelo afinador, enquanto a tela do afinador está
  aberta. O som é analisado na hora, no aparelho, e não é gravado nem enviado.

## Conferir um download

Todos os arquivos dos releases (APK do Android, DMG do Mac e IPA do
iPhone/iPad) são compilados a partir do código deste repositório pelo GitHub
Actions. Para cada um, o GitHub registra um
[atestado assinado de proveniência de build](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations).
Para conferir que um arquivo veio deste repositório, sem alterações:

```sh
gh attestation verify pestana-<versão>-android.apk -R batatinhafcbatatinha-pixel/pestana
gh attestation verify pestana-<versão>-mac.dmg     -R batatinhafcbatatinha-pixel/pestana
gh attestation verify pestana-<versão>-iphone.ipa  -R batatinhafcbatatinha-pixel/pestana
```

Cada release também traz os checksums SHA-256 em `SHA256SUMS.txt` e os
pacotes de atestado (`attestation-*.sigstore.json`), para conferir offline
ou com outras ferramentas do Sigstore.

Os APKs são assinados com o certificado cuja impressão digital SHA-256 é
`D0:B6:74:67:8D:72:D5:56:EA:74:91:80:BC:34:BF:90:41:C1:33:C2:BD:65:C5:0B:9B:06:E9:7F:2D:F0:47:40`.
Para conferir: `apksigner verify --print-certs pestana-<versão>-android.apk`.

## Verificações automáticas

- [Build](https://github.com/batatinhafcbatatinha-pixel/pestana/actions/workflows/build.yml):
  testes do núcleo em Rust e build de todas as plataformas a cada mudança.
- [CodeQL](https://github.com/batatinhafcbatatinha-pixel/pestana/actions/workflows/codeql.yml)
  analisa o app Kotlin, o núcleo Rust e os próprios workflows.
- O Dependabot propõe atualizações das dependências de Rust, Gradle e Actions.
- O [OpenSSF Scorecard](https://scorecard.dev/viewer/?uri=github.com/batatinhafcbatatinha-pixel/pestana)
  avalia as práticas de segurança do repositório.
- As actions dos workflows são fixadas por hash de commit, e cada job recebe
  só as permissões de que precisa.
