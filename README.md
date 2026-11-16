# Controle TV

App Android que procura TVs na rede (SSDP) e liga/desliga.

## Gerar o APK (sem instalar nada no PC)
1. Crie um repositório no GitHub e envie o conteúdo desta pasta.
2. Aba **Actions** > workflow **Build APK** > abra a execução concluída.
3. Em **Artifacts**, baixe **ControleTV-apk**, extraia e envie `app-debug.apk` ao celular.
4. No celular, permita "instalar apps desconhecidos" para o app que abrir o arquivo.

Alternativa: abra a pasta no Android Studio e use Build > Build APK(s).

## Ajustes nas TVs
- Samsung: Geral > Rede > Configurações de especialista > ligar com rede/Wi-Fi.
- LG: Geral > Dispositivos > Ligar com Wi-Fi (LG Connect Apps).
- Roku/TCL Roku: Sistema > Energia > Início rápido da TV.
- Sony: Rede > Configuração de rede doméstica > Controle IP (Autenticação: Normal e Pre-Shared Key) e Início remoto.
