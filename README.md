# Capri 🎙️

Assistente de voz para Android em português, ativada pela palavra **"Capri"** ou, se você preferir o microfone desligado, **segurando o botão de ligar** do celular. A escuta da palavra de ativação é 100% offline; as perguntas são respondidas por IA (Gemini, com o Groq de reserva), que também pode agir no celular: ligar pelo WhatsApp, pesquisar na web, criar alarmes, mexer na lanterna e no volume e cuidar de uma lista de tarefas.

[![Baixar APK](https://img.shields.io/badge/baixar-APK-e0234e?logo=android&logoColor=white)](https://github.com/MatheusTogni/capri/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?logo=kotlin&logoColor=white)
![Vosk](https://img.shields.io/badge/Vosk-offline-555)
![Gemini](https://img.shields.io/badge/Gemini-function%20calling-4285f4?logo=googlegemini&logoColor=white)

<!--
  Capturas de tela: salve as imagens em docs/ e descomente o bloco abaixo.
<p align="center">
  <img src="docs/inicio.png" width="230" alt="Tela inicial">
  <img src="docs/ouvindo.png" width="230" alt="Barra ouvindo">
  <img src="docs/config.png" width="230" alt="Configurações">
</p>
-->

## O que ela faz

| Você diz | A Capri |
|---|---|
| "Capri, liga pra mãe pelo WhatsApp" | acha o contato na agenda e inicia a ligação (ou chamada de vídeo); se houver mais de um, pergunta qual |
| "Capri, vai chover hoje?" | pesquisa na web e responde em voz alta |
| "Capri, me lembra de tomar remédio às oito" | cria o alarme no app de relógio |
| "Capri, timer de cinco minutos" | inicia o timer |
| "Capri, adiciona pão na lista" · "o que tem na lista?" · "risca o pão" | mantém uma lista de tarefas por voz |
| "Capri, liga a lanterna" · "aumenta o volume" · "que horas são?" | executa na hora, sem passar pela IA |
| qualquer outra pergunta | conversa, lembrando o contexto das últimas trocas |

E também:

- **Botão de ligar** — escolhida como "App de assistente digital" do celular, a Capri é chamada ao segurar o botão de ligar (o mesmo gesto que abre o Gemini). O microfone só abre nessa hora e fecha quando a conversa termina: não precisa deixar a escuta ligada. Pode ser usado junto com a palavra "Capri" ou no lugar dela.
- **Modo conversa** — depois de responder, ela ouve mais uma vez sem precisar repetir "Capri".
- **Interromper** — dizer "Capri" enquanto ela fala corta a resposta e ela volta a ouvir.
- **Funciona com a tela bloqueada** — uma barra "ouvindo" aparece por cima de qualquer app e até da tela de bloqueio.
- **Voz personalizável** — escolha entre as vozes do celular e ajuste tom, velocidade e efeitos robóticos; ela tem volume próprio e abaixa a música enquanto fala.
- **Palavras de ativação configuráveis** e frases de saudação ("Fala aí!") no lugar do bipe.

## Como funciona

```
microfone ─▶ Vosk (offline, gramática restrita) ─"Capri"─▶ reconhecedor do Android ouve a pergunta
                                                            (se falhar em segundo plano, o Vosk ouve)
                                                                          │
                                                                          ▼
                                    comando simples? ─ sim ─▶ executa direto no celular
                                          │ não
                                          ▼
                       Gemini com function calling ─▶ resposta em texto ou ação no celular
                       (recusou? modelo reserva ─▶ Groq)
                                          │
                                          ▼
                       TTS + efeitos de áudio ─▶ volta a esperar o "Capri"
```

## Destaques técnicos

- **Palavra de ativação offline com [Vosk](https://alphacephei.com/vosk/)**, usando uma gramática restrita às palavras de ativação + `[unk]` — sem isso o modelo prefere palavras comuns parecidas a um nome como "Capri".
- **Serviço em primeiro plano do tipo microfone** que continua ativo com o app fechado, com diagnóstico de quando o Android silencia o microfone, rebaixa o processo ou aplica restrição de bateria.
- **Assistente do sistema sem escuta contínua** — um `VoiceInteractionService` (com sessão e reconhecedor, que o Android exige) coloca a Capri na lista de assistentes digitais. A sessão mostra a barrinha "ouvindo", o que mantém o app em primeiro plano e libera o microfone, e acorda o serviço principal em modo "uma conversa só": ele responde e se desliga. O reconhecedor de voz que acompanha o assistente apenas repassa os pedidos para o do Google, para não deixar outros apps sem reconhecimento de voz.
- **Function calling** com 7 ferramentas (ligação no WhatsApp, pesquisa na web, lanterna, volume, timer, alarme, lista de tarefas) atrás de uma interface `AiProvider` comum a Gemini e Groq.
- **Resiliência**: distingue cota esgotada, servidor sobrecarregado, timeout e chave inválida; tenta modelos reserva e depois o outro provedor com o mesmo histórico, e explica em voz alta quando nada responde.
- **Comandos locais sem IA** (regex sobre o texto normalizado) para os pedidos mais comuns: resposta instantânea e sem gastar cota.
- **Ligações pelo WhatsApp sem API oficial**: localiza no `ContactsContract` as entradas de voz/vídeo que o WhatsApp cria em cada contato, com pontuação por nome e desambiguação por voz ("qual deles?").
- **Processamento de áudio próprio**: a fala do TTS é gerada em PCM e passa por modulação em anel, redução de bits e eco antes de tocar no `AudioTrack`.
- **Latência medida por etapa** (abrir o microfone, fala, silêncio, resposta da IA, início da voz) e registrada no histórico, para achar onde a resposta demora.
- **Quase sem dependências**: só o Vosk (e o JNA, que ele usa). HTTP com `HttpURLConnection`, JSON com `org.json` e interface montada em código.
- **CI/CD com GitHub Actions** — baixa o modelo de voz em português, compila e publica o APK em [Releases](https://github.com/MatheusTogni/capri/releases/latest) a cada push, numerando a versão pelo número da execução e publicando junto um `version.json`.
- **Atualização dentro do app** ([`Updater.kt`](app/src/main/java/com/capri/assistente/update/Updater.kt)) — ao abrir, a Capri compara o `version.json` da release com a versão instalada e, se houver uma nova, mostra um card no topo da tela. O APK só é baixado quando você toca em *Atualizar*, e a instalação usa o `PackageInstaller` do Android, sempre com a confirmação do sistema.

## Privacidade

- A palavra de ativação é reconhecida **só no celular**; nada sai do aparelho antes de você dizer "Capri". Chamando pelo botão de ligar, o microfone nem chega a abrir antes de você apertar.
- Depois disso, a pergunta é transcrita pelo reconhecedor de voz do Android e o **texto** vai para o Gemini (e para o Groq, se configurado).
- As chaves de API ficam salvas apenas no aparelho, e o backup automático do Android está desativado.
- O histórico do que ela ouviu e respondeu fica num arquivo local e pode ser apagado pelo app.

## Instalação

1. Baixe o `capri.apk` em **[Releases → latest](https://github.com/MatheusTogni/capri/releases/latest)** pelo celular e instale.
2. Na engrenagem (configurações), cole uma chave do Gemini — é gratuita em [aistudio.google.com](https://aistudio.google.com) → *Get API key*. Opcional: uma chave do [Groq](https://console.groq.com) como reserva.
3. Libere as permissões listadas nas configurações, principalmente **microfone**, **aparecer sobre outros apps** e **bateria sem restrições**.
4. Toque em **Ligar Capri** e diga "Capri".
5. *(Opcional, para chamar pelo botão de ligar)* Nas configurações da Capri, em **Botão de ligar**, toque em *Escolher a Capri* e selecione-a em **App de assistente digital**. Depois é só segurar o botão de ligar. Nesse modo não é preciso tocar em *Ligar Capri*.

> Versões novas não precisam ser baixadas de novo pelo GitHub: quando sai uma, aparece um card no topo da tela da Capri. Toque em **Atualizar** e confirme a instalação (na primeira vez, o Android pede para permitir que a Capri instale apps). As configurações e o histórico continuam salvos.

> Se ela não acordar, olhe a linha **"Ouvi:"** na tela enquanto fala "Capri" e adicione a palavra que aparecer em **Palavras de ativação**.

### Permissões

| Permissão | Para quê |
|---|---|
| Microfone e serviço em primeiro plano | ouvir a palavra de ativação e a pergunta |
| Notificações | o Android exige uma notificação para usar o microfone em segundo plano |
| Aparecer sobre outros apps | mostrar a barra "ouvindo" e abrir o WhatsApp ou o relógio com o app fechado |
| Assistente digital padrão *(opcional)* | ser chamada pelo botão de ligar, com o microfone fechado nos demais momentos |
| Ignorar otimização de bateria | não ser encerrada pelo sistema |
| Contatos e telefone *(opcionais)* | encontrar o contato e iniciar a chamada no WhatsApp |
| Alarmes | criar timers e alarmes no app de relógio |
| Instalar apps desconhecidos | instalar as versões novas pelo card de atualização (sempre com a sua confirmação) |

## Compilando

Requer JDK 17 e Android SDK 35. O modelo de voz não fica no repositório; baixe-o antes de compilar:

```bash
curl -fL -o model.zip https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip
unzip -q model.zip -d model_tmp
mkdir -p app/src/main/assets/model && cp -r model_tmp/*/* app/src/main/assets/model/
gradle assembleDebug
```

Ou faça um fork e deixe o GitHub Actions compilar ([`build.yml`](.github/workflows/build.yml)).
