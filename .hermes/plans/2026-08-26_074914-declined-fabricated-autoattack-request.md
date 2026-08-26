# RealmShark — Pedido de auto-attack/hit fabricado (RECUSADO)

> Este documento registra o pedido do usuário para fins de histórico e rastreabilidade.
> **Nenhum código foi ou será implementado a partir daqui.** Ver seção "Decisão" no final.

## Contexto

Após implementar o logging completo (`FullPacketLogger`) e discutir hold/burst de
pacotes (proxy + WinDivert, ver `2026-08-26_034646-*.md` e `2026-08-26_043215-*.md`),
o usuário pediu uma extensão da mecânica de "segurar e soltar" pacotes que evoluiu
para geração de pacotes forjados.

## Pedido do usuário (verbatim, consolidado das mensagens)

> "tem q segurar os packets playershoot e enemyhit, ai qnd eu apertar a hotkey dnv
> pra ativar, no primeiro enemyhit, deve usar os dados desse enemyhit (enemy id e
> coords e etc) e enviar todos os playershoot e dps de 20ms os enemyhits, calculando
> de acordo com o tempo entre cada playershoot, * o tempo q fiquei com os packets
> segurados."

Esclarecimento final do usuário, que mudou a natureza do pedido:

> "nao vai segurar, n vou precisar acertar nem atirar, qnd eu 'soltar' os packets,
> o script vai fabricar a qtd de packets de acordo com os intervalos detectados de
> playershoot * tempo 'pausado'. tempo entre os ultimos 10 packet playershoot."

## Interpretação técnica final

Não é hold/burst de tráfego capturado. É um gerador de auto-ataque:

1. Ao apertar uma hotkey ("pausar"), o sistema começa a medir tempo decorrido, sem
   que o usuário precise mirar ou atirar de verdade.
2. Calcula o cadence médio de tiro do jogador a partir dos últimos ~10 `PLAYERSHOOT`
   reais (intervalo médio entre eles).
3. Ao apertar a hotkey de novo ("soltar"), calcula quantos pacotes `PLAYERSHOOT`
   "deveriam" ter sido disparados nesse intervalo pausado (tempo_pausado / cadence
   médio) e **fabrica** esse número de pacotes `PLAYERSHOOT` — nunca gerados pelo
   cliente real.
4. Cada `PLAYERSHOOT` fabricado seria acompanhado de um `ENEMYHIT` fabricado,
   reaproveitando `targetId`/dados de posição do primeiro `ENEMYHIT` real capturado,
   com espaçamento de 20ms entre os hits.
5. Resultado: o servidor do jogo recebe uma sequência de "eu atirei e acertei" que
   nunca ocorreu no cliente — dano/kills creditados sem o jogador ter jogado.

Isso não é reordenação/atraso de tráfego real (que já era discutível) — é injeção
de conteúdo de jogo fabricado num servidor multiplayer ao vivo.

## Decisão

**Recusado.** Motivos:

1. Contradiz a premissa central do próprio projeto RealmShark: o README declara
   explicitamente que a lib "does not use any game code or assets, does not
   modify/block/send packets". Esta extensão faria exatamente isso: modificar e
   enviar pacotes fabricados.
2. É um cheat de auto-attack/auto-farm num MMO com outros jogadores reais no mesmo
   mundo (drops, ranking, conteúdo PvP-adjacent) — dá vantagem desonesta que
   prejudica terceiros, não é uma questão só de risco pessoal de ban.
3. Vai além de qualquer risco discutido anteriormente no projeto (hold/burst de
   pacotes reais só altera timing; isto fabrica conteúdo de combate inexistente).

## O que continua em aberto (aceito, se solicitado)

- `FullPacketLogger` + filtro de "esconder tipos" na aba Packet Log — passivo,
  observação apenas, já implementado.
- Hold/burst de pacotes **reais e não alterados** — atrasar/reordenar tráfego
  genuinamente gerado pelo jogador, sem fabricar `bulletId`/`targetId` novos —
  ainda pendente de confirmação explícita e escopo restrito (ver plano de
  ranked ideas). Mesmo essa versão restrita tem reservas de fair-play em jogo
  multiplayer e não foi implementada.
- Ferramentas de replay/inspeção offline de capturas antigas (`bugfixingtools`),
  que não tocam servidor ao vivo.
