# Capri 🎙️

Assistente de voz para Android em português, chamada **segurando o botão de ligar** do celular (escolhida como "App de assistente digital"). Não fica escutando nada: o microfone só abre quando você a chama e fecha quando a conversa termina. As perguntas são respondidas por IA (Groq, no plano gratuito), que também pode agir no celular: ligar pelo WhatsApp, pesquisar na web, criar alarmes, mexer na lanterna e no volume e cuidar de uma lista de tarefas.

[![Baixar APK](https://img.shields.io/badge/baixar-APK-e0234e?logo=android&logoColor=white)](https://github.com/MatheusTogni/capri/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?logo=kotlin&logoColor=white)
![Groq](https://img.shields.io/badge/Groq-function%20calling-f55036)

<!--
  Capturas de tela: salve as imagens em docs/ e descomente o bloco abaixo.
<p align="center">
  <img src="docs/inicio.png" width="230" alt="Tela inicial">
  <img src="docs/ouvindo.png" width="230" alt="Barra ouvindo">
  <img src="docs/config.png" width="230" alt="Configurações">
</p>
-->

## O que ela faz

Segure o botão de ligar e diga:

| Você diz | A Capri |
|---|---|
| "liga pra mãe pelo WhatsApp" | acha o contato na agenda e inicia a ligação (ou chamada de vídeo); se houver mais de um, pergunta qual |
| "vai chover hoje?" | pesquisa na web e responde em voz alta |
| "me lembra de tomar remédio às oito" | cria o alarme no app de relógio |
| "timer de cinco minutos" | inicia o timer |
| "adiciona pão na lista" · "o que tem na lista?" · "risca o pão" | mantém uma lista de tarefas por voz |
| "liga a lanterna" · "aumenta o volume" · "que horas são?" | executa na hora, sem passar pela IA |
| qualquer outra pergunta | conversa, lembrando o contexto das últimas trocas |

E também:

- **Só pelo botão de ligar** — escolhida como "App de assistente digital" do celular, a Capri é chamada ao segurar o botão de ligar (o mesmo gesto que abre o assistente do Google). Não há escuta contínua nem palavra de ativação: o microfone só abre nessa hora.
- **Modo conversa** — depois de responder, ela ouve mais uma vez sem precisar segurar o botão de novo.
- **Interromper** — apertar o botão de ligar de novo enquanto ela fala corta a resposta e ela volta a ouvir.
- **Funciona com a tela bloqueada** — uma barra "ouvindo" aparece por cima de qualquer app e até da tela de bloqueio.
- **Voz personalizável** — escolha entre as vozes do celular e ajuste tom, velocidade e efeitos robóticos; ela tem volume próprio e abaixa a música enquanto fala.
- **Saudações configuráveis** ("Fala aí!") no lugar do bipe.

## Como funciona

```
segurar o botão de ligar ─▶ sessão do assistente (mostra a barra "ouvindo") ─▶ acorda o serviço da Capri
                                                                          │
                                                                          ▼
                                       saudação + reconhecedor do Android ouve a pergunta
                                                                          │
                                                                          ▼
                                    comando simples? ─ sim ─▶ executa direto no celular
                                          │ não
                                          ▼
                       Groq (gpt-oss) com function calling ─▶ resposta, busca na web ou ação no celular
                       (modelo sem cota? o reserva responde; os dois sem cota? avisa e espera)
                                          │
                                          ▼
                       TTS + efeitos de áudio ─▶ ouve de novo (modo conversa) ou desliga
```

## Destaques técnicos

- **Assistente do sistema, sem escuta contínua** — um `VoiceInteractionService` (com sessão e reconhecedor, que o Android exige) coloca a Capri na lista de assistentes digitais. A sessão mostra a barrinha "ouvindo", o que mantém o app em primeiro plano e libera o microfone, e acorda o serviço principal em modo "uma conversa só": ele responde e se desliga. O reconhecedor de voz que acompanha o assistente apenas repassa os pedidos para o do Google, para não deixar outros apps sem reconhecimento de voz.
- **Serviço em primeiro plano do tipo microfone, só durante a conversa** — sobe quando o botão é apertado e cai quando a conversa termina, então não há o que o Android silenciar ou rebaixar em segundo plano.
- **Function calling** com 7 ferramentas (ligação no WhatsApp, pesquisa na web, lanterna, volume, timer, alarme, lista de tarefas) atrás de uma interface `AiProvider`. A busca na web do Groq (`browser_search`) vai na mesma chamada das funções, então uma pergunta que precisa de pesquisa custa uma chamada só; se o Groq recusar a mistura, ela cai sozinha para o modo de duas chamadas.
- **Feita para o plano gratuito**: distingue cota esgotada, servidor sobrecarregado, timeout e chave inválida; só troca para o modelo reserva em recusas rápidas (sem somar esperas), lê o `retry-after` do 429 para não fazer pedido nenhum enquanto a cota não volta e avisa em voz alta em quanto tempo ela volta. Modelo principal e reserva se escolhem numa lista nas configurações.
- **Comandos locais sem IA** (regex sobre o texto normalizado) para os pedidos mais comuns: resposta instantânea e sem gastar cota.
- **Ligações pelo WhatsApp sem API oficial**: localiza no `ContactsContract` as entradas de voz/vídeo que o WhatsApp cria em cada contato, com pontuação por nome e desambiguação por voz ("qual deles?").
- **Processamento de áudio próprio**: a fala do TTS é gerada em PCM e passa por modulação em anel, redução de bits e eco antes de tocar no `AudioTrack`.
- **Latência medida por etapa** (abrir o microfone, fala, silêncio, resposta da IA, início da voz) e registrada no histórico, para achar onde a resposta demora.
- **Sem dependências de terceiros**: HTTP com `HttpURLConnection`, JSON com `org.json` e interface montada em código.
- **CI/CD com GitHub Actions** — compila e publica o APK em [Releases](https://github.com/MatheusTogni/capri/releases/latest) a cada push, numerando a versão pelo número da execução e publicando junto um `version.json`.
- **Atualização dentro do app** ([`Updater.kt`](app/src/main/java/com/capri/assistente/update/Updater.kt)) — ao abrir, a Capri compara o `version.json` da release com a versão instalada e, se houver uma nova, mostra um card no topo da tela. O APK só é baixado quando você toca em *Atualizar*, e a instalação usa o `PackageInstaller` do Android, sempre com a confirmação do sistema.

## Privacidade

- O microfone **só abre depois que você segura o botão de ligar**; fora disso, nada é ouvido.
- A pergunta é transcrita pelo reconhecedor de voz do Android e o **texto** vai para o Groq.
- A chave de API fica salva apenas no aparelho, e o backup automático do Android está desativado.
- O histórico do que ela ouviu e respondeu fica num arquivo local e pode ser apagado pelo app.

## Instalação

1. Baixe o `capri.apk` em **[Releases → latest](https://github.com/MatheusTogni/capri/releases/latest)** pelo celular e instale.
2. Na engrenagem (configurações), cole uma chave do Groq — o plano gratuito basta: [console.groq.com](https://console.groq.com) → *API Keys*. Escolha o modelo principal e o reserva na lista (padrão: `gpt-oss-20b` e `gpt-oss-120b`).
3. Libere as permissões listadas nas configurações, principalmente o **microfone**.
4. Na tela inicial, toque em **Escolher como assistente** e selecione a Capri em **App de assistente digital**. Depois é só segurar o botão de ligar e falar.

> Versões novas não precisam ser baixadas de novo pelo GitHub: quando sai uma, aparece um card no topo da tela da Capri. Toque em **Atualizar** e confirme a instalação (na primeira vez, o Android pede para permitir que a Capri instale apps). As configurações e o histórico continuam salvos.

### Permissões

| Permissão | Para quê |
|---|---|
| Microfone e serviço em primeiro plano | ouvir a pergunta durante a conversa |
| Notificações | o Android exige uma notificação para usar o microfone enquanto ela conversa |
| Assistente digital padrão | ser chamada pelo botão de ligar (é a única forma de ativá-la) |
| Aparecer sobre outros apps | abrir o WhatsApp ou o relógio quando o app não está aberto |
| Contatos e telefone *(opcionais)* | encontrar o contato e iniciar a chamada no WhatsApp |
| Alarmes | criar timers e alarmes no app de relógio |
| Instalar apps desconhecidos | instalar as versões novas pelo card de atualização (sempre com a sua confirmação) |

## Compilando

Requer JDK 17 e Android SDK 35:

```bash
gradle assembleDebug
```

Ou faça um fork e deixe o GitHub Actions compilar ([`build.yml`](.github/workflows/build.yml)).
