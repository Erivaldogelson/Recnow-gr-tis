# Recnow GR tis

Recnow GR tis e a edicao com anuncios do Recnow, um gravador de tela nativo para Android feito em Kotlin e Jetpack Compose com Material 3, cores dinamicas do Material You e animacoes suaves no fluxo principal.

## Recursos

- Gravacao de tela em MP4 usando `MediaProjection` e `MediaRecorder`.
- Opcoes de qualidade:
  - 720p a 60 Hz
  - Full HD a 60 Hz
  - 1060p a 60 Hz
  - 2K a 60 Hz
  - 1060p a 120 Hz
  - 2K a 120 Hz
  - 4K a 30 Hz
  - 4K a 120 Hz
- Interface simples em Compose Material 3 com cores dinamicas do sistema.
- Banner de anuncios na tela principal usando Google Mobile Ads.
- Compra unica `remove_ads` via Google Play Billing para remover anuncios.
- Textos principais acompanham o idioma do sistema em ingles ou portugues.
- Icone adaptativo com camada monochrome para temas de icones do Material You sem distorcer o desenho.
- Tile de Configuracoes Rapidas para abrir um painel central com resolucao e microfone antes de iniciar/parar gravacao direto pela barra de controle do Android e pelo painel rapido da One UI 4+ quando o usuario adicionar o bloco.
- Notificacao foreground continua com cronometro, acao para parar e hints de Live Updates/Now Bar no Android 16 quando o sistema permitir promocao de notificacao.
- Segunda notificacao apos parar a gravacao com acoes para salvar, visualizar ou apagar o video.
- Icone de notificacao/Now Bar e logo em circulo preenchido (`●`) com vermelho vibrante.
- Cor de destaque vermelho forte (`#FF0000`) nas notificacoes e controles principais.

## Como usar

1. Abra o app e escolha a resolucao e se deseja gravar com microfone.
2. Toque em **Iniciar gravacao** e aceite a permissao de captura de tela do Android.
3. Para parar, use o botao no app ou a acao da notificacao.

## Tile da barra de controle

O Android nao permite que um app adicione sozinho um bloco ao painel rapido. Para usar:

1. Abra o painel rapido/central de controle.
2. Toque em editar.
3. Adicione o bloco **Recnow**.
4. Toque no bloco e escolha resolucao e microfone no painel central.
5. Confirme na janela oficial do Android se deseja gravar a tela inteira ou apenas um app.
6. Toque de novo para parar quando ja estiver gravando.

O seletor de tela inteira/app pertence ao Android e nao pode ser customizado pelo app. Por isso o Recnow mostra a escolha de qualidade antes de abrir essa janela oficial.

## Android 16 Live Updates / Now Bar

O app declara `POST_PROMOTED_NOTIFICATIONS` e, em Android 16+, solicita que a notificacao de gravacao seja promovida como Live Update. A notificacao de gravacao e continua, usa cronometro e texto curto critico para o estado recolhido.

Quando a gravacao para, o app mostra uma segunda notificacao promovida com o texto **Tela gravada**. Ao expandir, aparecem as acoes:

- **Salvar**: mantem o video em `Movies/Recnow` e remove a notificacao.
- **Visualizar**: abre o video no app padrao de video/galeria.
- **Apagar**: remove o video salvo e remove a notificacao.

Observacao: a exibicao exata da Now Bar depende do fabricante, versao do sistema e permissao do usuario. As regras atuais do Android nao permitem custom notification views nem `setColorized(true)` em Live Updates promovidas, entao a cor final do chip pode ser controlada pelo sistema.

As notificacoes usam texto expandido explicito para evitar casos em que a Now Bar aparece vazia ao expandir em algumas versoes do sistema.
Ao finalizar, a notificacao de gravacao e removida primeiro e a segunda Live Update e publicada com pequeno atraso e reforco de atualizacao para aparecer como chip recolhido mais rapidamente em builds que atrasam a promocao.

Audio interno agora usa `AudioPlaybackCapture` no Android 10+ para os modos **Midia** e **Midia + microfone**. O Android so permite capturar audio de apps que autorizam captura por politica do sistema.

O painel central usa card solido sem blur. Camera selfie em overlay para se gravar em Full HD/4K durante a captura ainda exige um servico de camera flutuante com permissao de sobreposicao.

Live Updates/Now Bar sao notificacoes promovidas pelo sistema Android. O app pode silenciar e pedir promocao imediata, mas nao pode exibir uma Now Bar sem existir uma notificacao de base, porque essa e a propria API do Android.

## Monetizacao

Esta edicao usa os IDs oficiais de teste do AdMob para compilar e testar com seguranca:

- App ID: `ca-app-pub-3940256099942544~3347511713`
- Banner: `ca-app-pub-3940256099942544/6300978111`

Antes de publicar em producao, troque esses valores em `app/src/main/res/values/strings.xml` e `app/src/main/res/values-pt/strings.xml` pelos IDs reais do AdMob.

Para remover anuncios, crie no Google Play Console um produto gerenciado com o ID:

```text
remove_ads
```

O app consulta esse produto, inicia a compra e salva/restaura o direito localmente quando o usuario possuir a compra.

## Build

Requisitos:

- Android SDK 36
- JDK 21 ou JDK 17
- Gradle wrapper do projeto

Comando:

```powershell
.\gradlew.bat assembleRelease
```

Para assinar com uma chave propria, defina:

- `RECNOW_RELEASE_STORE_FILE`
- `RECNOW_RELEASE_STORE_PASSWORD`
- `RECNOW_RELEASE_KEY_ALIAS`
- `RECNOW_RELEASE_KEY_PASSWORD`

Sem essas variaveis, o build usa o debug keystore local apenas para gerar um APK instalavel de desenvolvimento.
