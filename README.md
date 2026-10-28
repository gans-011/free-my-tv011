<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Free My TV 011

**Uma tela inicial rápida e sem anúncios para Google TV e Android TV, agora com fileiras de filmes e séries no estilo Apple TV.**

Baseado no [Free My TV](https://github.com/simbasjungles/free-my-tv) do [@simbasjungles](https://github.com/simbasjungles).
Código aberto. Feito com Claude Code. Um botão para desfazer.

[Instalar](#instalar) · [O que muda nesta versão](#o-que-muda-nesta-versão) · [Configurações](#configurações) · [Voltar ao original](#voltar-ao-original) · [README original (inglês)](README.en.md)

<img src=".github/preview.webp" width="880" alt="A tela inicial do Free My TV: a prateleira de apps em vidro, as indicações em alta e a gaveta com o clima">

</div>

## O que muda nesta versão

Tudo o que o Free My TV original faz continua aqui. A novidade:

- **Fileiras de pôsteres, como no app da Apple TV.** Na prateleira de apps, aperte **↓** e a tela sobe para dar lugar às fileiras:
  - *Filmes populares*
  - *Séries populares*
  - *Lançamentos*
  - *Mais bem avaliados*
  - *Em alta na Netflix*, *Em alta na Prime Video*… uma fileira para cada app de streaming que a sua TV tem.
- **← →** anda pela fileira, **↑ ↓** troca de fileira e **OK** abre o título no app que tem ele. **↑** na primeira fileira volta para a prateleira; **↓** na última abre a gaveta (clima, entradas, cotações).
- Só aparecem títulos dos apps que você tem instalados. As fileiras se atualizam a cada 6 horas e os pôsteres ficam guardados na TV.
- Precisa da [chave do TMDB](#a-chave-do-tmdb) (grátis). Sem ela, a tela inicial funciona normalmente, só sem as fileiras.

E o que já vinha do original:

- **Sem anúncios.** Nada de fileiras patrocinadas, banners ou vídeos tocando sozinhos.
- **Seus apps em destaque**, numa prateleira de vidro. Você escolhe quais e a ordem, na própria TV.
- **Clima e cotações** a um botão de distância. Fica escura depois do pôr do sol da sua cidade.
- **Seguro.** Sem root. Nada é desinstalado. Um botão traz de volta a tela inicial de fábrica.

## Instalar

Uns 15 minutos. Você precisa de uma TV com Google TV ou Android TV (Android 8 ou mais novo) e um computador com Windows na mesma rede Wi-Fi. Foi assim que eu instalei.

### Jeito fácil: com um assistente de IA

1. **Na TV**, ligue o modo de desenvolvedor: Configurações → Sistema → Sobre → aperte *Versão do SO do Android TV* 7 vezes. Depois Configurações → Sistema → Opções do desenvolvedor → ligue a **Depuração USB**. Anote o IP da TV (Configurações → Rede e Internet).
2. **No computador**, abra o [Claude Code](https://claude.com/claude-code) e diga:
   > Instale o Free My TV (github.com/gans-011/free-my-tv011) na minha TV no IP 192.168.1.50. Eu moro em São Paulo.

   Ele baixa o código, instala o que falta, gera o app e coloca na TV, e pergunta antes de qualquer coisa importante. Troque pelo IP da sua TV e pela sua cidade.
3. **Na TV**, abra *Free My TV* → *Início* → *Free My TV*. A TV pergunta *Permitir depuração USB?*: marque *Sempre permitir* e aceite.

### Jeito manual (Windows)

**1. Na TV:** faça o passo 1 do jeito fácil.

**2. Instale as ferramentas.** Abra o PowerShell e rode:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Google.PlatformTools
winget install Git.Git
```

Isso instala o Java 17, o `adb` e o Git (que vem com o Git Bash). Feche e abra o terminal de novo depois.

**3. Baixe o Android SDK.** Em [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only), baixe as *Command line tools only* para Windows. Crie a pasta `C:\Users\<você>\android-sdk\cmdline-tools\latest` e descompacte lá dentro (ela tem que ter `bin`, `lib`…). Depois, no Git Bash:

```bash
~/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat --sdk_root="C:/Users/<você>/android-sdk" "build-tools;34.0.0" "platforms;android-34"
```

Aceite as licenças com `y`.

**4. Baixe o código**, no Git Bash:

```bash
git clone https://github.com/gans-011/free-my-tv011.git
cd free-my-tv011
```

**5. Gere o app:**

```bash
export ANDROID_HOME="C:/Users/<você>/android-sdk"
./build.sh
```

> **Importante no Windows:** escreva o `ANDROID_HOME` no formato `C:/Users/...`, com barras normais. Com `/c/Users/...` o build falha com `Unable to find method metafactory`.

A primeira vez cria o `config.properties`. Abra ele, coloque a sua cidade e a sua [chave do TMDB](#a-chave-do-tmdb) (veja [Configurações](#configurações)) e rode `./build.sh` de novo. Tem que terminar com `Built FreeMyTV.apk`.

**6. Instale na TV:**

```bash
adb connect 192.168.1.50:5555
adb install -r FreeMyTV.apk
```

Na primeira conexão, a TV pergunta *Permitir depuração USB?*: marque *Sempre permitir* e aceite.

Se a instalação parecer travada, olhe a TV: no Android 14 o Google Play Protect pode segurar com *App arriscado bloqueado* ("criado para uma versão mais antiga do Android"). Escolha *Mais detalhes* → *Instalar mesmo assim*. As atualizações não perguntam de novo.

Em TVs **TCL**, deixe o app rodar em segundo plano (a TCL bloqueia por padrão e a troca nunca acontece):

```bash
adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
```

**7. Troque a tela inicial, na TV.** Antes, desconecte o computador (no Android 8 a 10 a TV pode mandar a resposta para ele):

```bash
adb disconnect 192.168.1.50:5555
```

Depois, na TV: *Free My TV* → *Início* → *Free My TV*. A TV pergunta *Permitir depuração USB?* mais uma vez, agora para o próprio Free My TV: marque *Sempre permitir* e aceite.

Pronto. Aperte **Home** no controle e depois **↓** para ver as fileiras.

### Atualizar

```bash
git pull
./build.sh
adb connect 192.168.1.50:5555
adb install -r FreeMyTV.apk
```

Guarde o arquivo `freemytv.keystore`: as atualizações precisam ser assinadas com a mesma chave.

## Configurações

O `config.properties` é criado no primeiro build. Quase tudo também dá para mudar na TV, em *Free My TV* → *Ajustes do Início*, e o que você escolher na TV vale mais.

| Chave | O que faz | Exemplo |
|---|---|---|
| `tmdb_key` | Sua chave do TMDB, para as indicações e as fileiras | (sua chave) |
| `tmdb_region` | Seu país, com duas letras: quais títulos estão em quais apps | `BR` |
| `latitude`, `longitude` | Sua cidade, para o clima e o pôr do sol | `-23.5505`, `-46.6333` |
| `city` | O nome no cartão do clima | `São Paulo` |
| `language` | `auto` segue a TV; ou `pt`, `en`, `es`… | `auto` |
| `show_crypto` | Mostra cotações de criptomoedas | `false` |
| `currency` | Moeda das cotações | `BRL` |
| `temperature` | `C`, `F` ou `auto` | `C` |

As outras opções estão no [README original](README.en.md#settings).

### A chave do TMDB

É grátis e leva dois minutos: crie uma conta em [themoviedb.org](https://www.themoviedb.org/signup), abra [Configurações → API](https://www.themoviedb.org/settings/api) e peça uma chave de desenvolvedor. Cole o *API Read Access Token* (ou a *API Key*) depois de `tmdb_key=`. A chave é sua: o `config.properties` nunca vai para o GitHub.

## Voltar ao original

Na TV: *Free My TV* → *Início* → *Original*. A tela inicial de fábrica, o protetor de tela e todos os apps voltam como eram. Se a nova tela inicial travar três vezes em cinco minutos, ela volta sozinha.

Para remover de vez, troque para *Original* primeiro e depois desinstale o *Free My TV* (Configurações → Apps).

## O que ele muda na TV

- Vira a tela inicial e o protetor de tela.
- Desliga, sem desinstalar, a tela inicial de fábrica e alguns apps de propaganda da marca. A lista completa está no [README original](README.en.md#what-it-changes).
- Imagem, som, entradas, controle, serviços do Google e a Play Store ficam numa lista protegida e nunca são desligados.

## LG e Samsung

As fileiras de pôsteres desta versão existem só no Google TV e Android TV. Para TVs LG (webOS) e Samsung (Tizen), siga o [README original](README.en.md#lg-tvs-webos).

## Créditos

- Projeto original: [Free My TV](https://github.com/simbasjungles/free-my-tv), de [@simbasjungles](https://github.com/simbasjungles) ([freemytv.io](https://freemytv.io)). Todos os outros créditos estão no [README original](README.en.md#credits).
- Feito com [Claude Code](https://claude.com/claude-code).
- Este produto usa a API do TMDB, mas não é endossado nem certificado pelo [TMDB](https://www.themoviedb.org/).
- Sem ligação com Google, TCL ou qualquer serviço de streaming. Nomes e logos dos apps pertencem aos seus donos.

## Licença

[MIT](LICENSE), a mesma do projeto original.
