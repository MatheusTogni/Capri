# Capri 🎙️

Assistente de voz para Android com palavra de ativação "Capri", usando Vosk (offline) + Gemini.

## Como gerar o APK
1. Suba este projeto para um repositório no GitHub (branch `main`).
2. A aba **Actions** compila sozinha (uns 5 minutos).
3. Baixe o `capri.apk` na página **Releases** pelo celular e instale.

## No celular
1. Cole a chave do Gemini (aistudio.google.com → Get API key) e toque em **Salvar**.
   Opcional: cole também uma chave do Groq (console.groq.com → API Keys), que responde quando o Gemini recusar.
2. Toque em **Liberar da economia de bateria** e marque a Capri como "sem restrições".
3. Toque em **Ligar Capri**, aceite as permissões e diga "Capri".

## Se ela não acordar
Olhe a linha "Ouvi:" na tela enquanto fala "Capri". Copie a palavra que aparecer
para o campo **Palavras de ativação** e salve.
