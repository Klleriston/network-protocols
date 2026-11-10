# netlab — Laboratório de TCP/UDP em tempo real

**Data:** 2026-11-10
**Status:** Aprovado em conversa; aguardando revisão do spec escrito

## 1. Objetivo

Projeto pessoal de aprendizado para **observar ao vivo** como a camada de transporte funciona, comparando TCP e UDP entre duas máquinas reais na mesma rede local.

### 1.1 Focos de aprendizado

1. **Conexão e ciclo de vida:** handshake de 3 vias, encerramento com FIN e com RST, conexão recusada, e o contraste com o UDP, que não tem conexão.
2. **Confiabilidade versus perda:** o TCP garante entrega e ordem; o UDP perde e reordena sob uma rede ruim (simulada).
3. **Desempenho:** RTT, jitter, vazão, efeito do algoritmo de Nagle e o comportamento sob saturação.

### 1.2 Fora de escopo

- Inspeção de cabeçalhos e pacotes crus (estilo Wireshark).
- Controle de experimentos a partir do painel (controle remoto). Pode entrar depois; a seção 9 diz como.
- App nativo de iPhone. O iPhone só visualiza o painel.
- Simulação de perda no TCP. Não é possível em Java puro; o painel exibe o zero de perdas do TCP como contraste.

### 1.3 Critério de sucesso

O roteiro manual da seção 10.4 é executado com o Windows rodando `server`, o Mac rodando `client` e o iPhone exibindo o painel, e cada experimento mostra no painel o efeito esperado.

## 2. Topologia

```
┌──────────────────────┐        TCP :7000 / UDP :7001        ┌──────────────────────┐
│ MacBook              │ ◀─────────────────────────────────▶ │ Desktop Windows      │
│ java -jar netlab.jar │                                     │ java -jar netlab.jar │
│   client --target IP │                                     │   server             │
│ prompt interativo    │                                     │ echo TCP + UDP       │
│ painel HTTP :8080    │                                     │ log no console       │
└──────────┬───────────┘                                     └──────────────────────┘
           │ HTTP + SSE
           ▼
┌──────────────────────┐
│ iPhone (Safari)      │
│ http://<ip-mac>:8080 │
└──────────────────────┘
```

- O **cliente** orquestra os experimentos, mede tudo e serve o painel.
- O **servidor** apenas devolve (echo) cada mensagem e registra eventos de conexão no próprio console.
- Toda medição é feita com o relógio do cliente, então não há sincronização de relógios entre as máquinas.

## 3. Stack

- Java 21 (virtual threads, records, sealed interfaces).
- Maven, um único módulo, `groupId` `dev.klleriston`, `artifactId` `netlab`, gerando um JAR executável (`maven-jar-plugin` com `Main-Class`).
- Sem dependências em tempo de execução. Rede com `java.net` (`Socket`, `ServerSocket`, `DatagramSocket`); painel com `com.sun.net.httpserver.HttpServer`.
- Testes com JUnit 5 (dependência apenas de teste).
- Pacote base: `dev.klleriston.netlab`.

## 4. Componentes

| Pacote | Responsabilidade | Depende de |
|---|---|---|
| `cli` | `Main`: lê o modo (`server` / `client`) e as flags; o prompt interativo do cliente; parsing de comandos | todos |
| `wire` | Formato e codificação das mensagens de teste | — |
| `server` | `TcpEchoServer`, `UdpEchoServer` | `wire` |
| `impair` | `ImpairedDatagramSocket`: perda, atraso + jitter e reordenação no UDP | — |
| `experiment` | Interface `Experiment` e os quatro experimentos | `wire`, `impair`, `event` |
| `event` | Records de evento (sealed) e `EventBus` | — |
| `metrics` | `MetricsAggregator` → `MetricsSnapshot` | `event` |
| `dashboard` | `DashboardServer` (HTTP + SSE) e `index.html` estático | `event`, `metrics` |

**Regra de fronteira:** `wire`, `impair`, `event` e `metrics` não abrem sockets nem fazem I/O de rede, e são testáveis isoladamente. Apenas `server`, `experiment` e `dashboard` tocam a rede.

**Injeção para testes:** componentes que dependem de tempo recebem um `LongSupplier nanoClock`; componentes aleatórios recebem um `java.util.Random`. Os padrões de produção são `System::nanoTime` e `new Random()`.

### 4.1 Linha de comando

```
java -jar netlab.jar server [--tcp-port 7000] [--udp-port 7001]
java -jar netlab.jar client --target <ip> [--tcp-port 7000] [--udp-port 7001] [--http-port 8080]
```

## 5. Protocolo de mensagens (`wire`)

Mensagem de teste (`TestMessage` record):

| Campo | Tipo | Significado |
|---|---|---|
| `seq` | `long` | Número de sequência, crescente por rodada |
| `sentAtNanos` | `long` | `nanoClock` do cliente no envio |
| `payload` | `byte[]` | Preenchimento (tamanho configurável; padrão 64 bytes) |

Codificação binária big-endian: `seq (8) | sentAtNanos (8) | payloadLength (4) | payload`.

- **TCP:** cada mensagem é precedida por um `int` com o tamanho total (prefixo de tamanho), escrito com `DataOutputStream` e lido com `DataInputStream.readFully`. Isso é necessário porque o TCP é um fluxo de bytes sem fronteiras de mensagem.
- **UDP:** uma mensagem por datagrama, sem prefixo.
- **Tamanho máximo:** 64 KiB. Acima disso, `MessageTooLargeException`.

O servidor devolve os bytes recebidos sem alteração.

## 6. Fluxo de dados

### 6.1 UDP

- Cada experimento UDP usa **duas threads**: um *sender* em ritmo fixo e um *receiver* de echos.
- O cliente mantém um mapa `seq → sentAtNanos` dos pacotes pendentes.
- **Echo recebido:** `rttNanos = now − sentAtNanos` e o evento `PacketEchoed`.
- **Perda:** um pendente sem echo após o timeout (padrão de 1 s) vira `PacketLost`. Um echo que chega depois do timeout é ignorado e contado como "tardio".
- **Reordenação:** um echo com `seq` menor que o maior `seq` já recebido vira `PacketReordered`.
- **Jitter:** estimador da RFC 3550, `J += (|D| − J) / 16`, com `D` igual à diferença entre RTTs consecutivos.

### 6.2 TCP

- Uma conexão por rodada, com o mesmo formato de mensagem.
- O tempo de handshake é medido em torno de `socket.connect()` e publicado como `ConnectionEstablished(handshakeMicros)`.
- O TCP nunca apresenta perda nem reordenação para a aplicação. O painel mostra esses zeros ao lado do UDP de propósito.

### 6.3 Eventos

```
Experiment ─publish─▶ EventBus ─┬─▶ MetricsAggregator ──(250 ms)──▶ MetricsSnapshot ─▶ EventBus
                                └─▶ DashboardServer ──SSE /events──▶ navegador
```

- `Event` é uma `sealed interface` com records: `ExperimentStarted`, `ExperimentFinished`, `ExperimentAborted`, `ExperimentFailed`, `ConnectionEstablished`, `ConnectionClosed(graceful)`, `ConnectionReset`, `ConnectionRefused`, `ConnectTimeout`, `UdpPortUnreachable`, `PacketSent`, `PacketEchoed`, `PacketLost`, `PacketReordered`, `MetricsSnapshot`, `ImpairmentChanged`, `Suppressed(count)` (este último gerado apenas pelo `DashboardServer`).
- Todo evento tem `timestampNanos`, `protocol` (`TCP` / `UDP` / `NONE`) e `toJson()` escrito à mão.
- **`EventBus`:** cada assinante tem uma fila própria limitada (padrão de 10 000) consumida por uma thread própria. Quando a fila está cheia, descarta o item mais antigo e incrementa um contador de descartes. `publish` **nunca bloqueia**.
- **`MetricsAggregator`:** a cada 250 ms publica um `MetricsSnapshot` por protocolo, com:
  - enviados, ecoados, perdidos, reordenados e tardios;
  - perda %;
  - RTT mín, média, p95 e máx (µs);
  - jitter (µs);
  - vazão (bytes ida e volta por segundo).
  
  Zera os contadores no `ExperimentStarted`.

### 6.4 Painel (`dashboard`)

- `GET /` serve `index.html` (recurso do classpath; HTML, CSS e JS puros, sem framework).
- `GET /events` responde `text/event-stream` com linhas `event: <tipo>` e `data: <json>`.
- O SSE **não** encaminha `PacketSent` nem `PacketEchoed`; essa informação chega agregada nos snapshots.
- `PacketLost` e `PacketReordered` são limitados a 50 por segundo por conexão SSE, com um evento `Suppressed(count)` para o excedente.
- Ao conectar, o cliente SSE recebe imediatamente o último snapshot de cada protocolo.
- A página exibe:
  - a linha do tempo de eventos de ciclo de vida;
  - cartões TCP e UDP lado a lado com as métricas;
  - um gráfico de RTT ao longo do tempo (`<canvas>`);
  - o perfil de `impair` ativo.
- O layout é legível na largura de um iPhone.
- Ao iniciar, o cliente imprime a URL do painel com o IP da interface de rede local (IPv4 não loopback).

## 7. Experimentos

O cliente roda um prompt interativo; o painel permanece no ar entre execuções.

```
netlab> run <experimento> [chave=valor ...]
netlab> impair loss=<0-100> delay=<ms> jitter=<ms> reorder=<0-100>
netlab> impair preset=<wifi-ruim|3g>
netlab> impair off
netlab> stop
netlab> help
netlab> quit
```

| Experimento | Foco | Comportamento | Parâmetros (padrão) |
|---|---|---|---|
| `lifecycle` | Ciclo de vida | Roteiro: TCP conecta e fecha com FIN; TCP conecta e fecha com RST (`setSoLinger(true, 0)`); TCP para uma porta fechada (recusada); UDP ao servidor (sem handshake); UDP para uma porta fechada (silêncio ou `PortUnreachableException`, com `DatagramSocket` conectado) | `closed-port=7999` |
| `reliability` | Confiabilidade | Envia `count` mensagens numeradas por TCP e depois por UDP, com o `impair` ativo no UDP; relatório de entregues, perdidos e reordenados por protocolo | `count=1000`, `rate=200` msg/s |
| `latency` | Desempenho | Ping-pong de uma mensagem por vez, em TCP e UDP; RTT e jitter; `nagle=on` deixa `TCP_NODELAY=false` | `samples=200`, `nagle=off`, `size=64` |
| `throughput` | Desempenho | TCP: envio contínuo pelo tempo dado. UDP: envio a uma taxa fixa; perda acima da capacidade | `seconds=10`, `udp-rate=20mbps`, `size=1200` |

Perfis de `impair`:

| Perfil | loss | delay | jitter | reorder |
|---|---|---|---|---|
| `wifi-ruim` | 5% | 30 ms | 20 ms | 3% |
| `3g` | 2% | 150 ms | 60 ms | 1% |

O `impair` se aplica apenas ao UDP do cliente, nos dois sentidos (envio e recepção), e permanece ativo até `impair off`. Os valores são por sentido: numa ida e volta o efeito é cerca de 2× (perda de ~10% com `loss=5`). Cada mudança publica `ImpairmentChanged`.

## 8. Tratamento de erros

**Princípio:** erro de rede é dado, não falha.

### 8.1 Resultados de rede viram eventos

| Situação | Detecção em Java | Evento |
|---|---|---|
| Porta TCP fechada | `ConnectException` | `ConnectionRefused` |
| Cliente fecha com RST (`setSoLinger(true, 0)`) | — (ação do próprio cliente) | `ConnectionClosed(graceful=false)` |
| Par fecha com RST (ex.: servidor encerrado no meio) | `SocketException` "Connection reset" | `ConnectionReset` |
| Par fecha com FIN | `read()` retorna `-1` / `EOFException` | `ConnectionClosed(graceful=true)` |
| Handshake sem resposta | `SocketTimeoutException` no connect (3 s) | `ConnectTimeout` |
| ICMP de porta inalcançável | `PortUnreachableException` | `UdpPortUnreachable` |
| Echo UDP não volta | timeout de 1 s | `PacketLost` |

Timeout de leitura TCP: 5 s.

### 8.2 Ambiente

- **Servidor inalcançável num `run`:** o experimento publica `ExperimentFailed`, e o terminal mostra uma dica sobre o servidor rodando e o Firewall do Windows. O `server` imprime ao subir as portas e um aviso para liberar o Java no firewall.
- **`BindException` ao iniciar:** encerra com uma mensagem indicando a flag para trocar a porta.
- **Comando inválido:** imprime o uso; o prompt continua.

### 8.3 Controle de execução

- Um experimento por vez. Um `run` durante outro é recusado com mensagem.
- `stop` ou Ctrl+C durante um `run` interrompe as threads, fecha os sockets e publica `ExperimentAborted`. Ctrl+C sem experimento em andamento (ou um segundo Ctrl+C) encerra o cliente.
- Todos os sockets são abertos com `try-with-resources`.

### 8.4 Servidor e painel

- Exceção numa conexão TCP do servidor é logada e não afeta as outras (uma virtual thread por conexão).
- Prefixo de tamanho maior que 64 KiB: fecha a conexão e registra no log.
- Datagrama malformado: ignorado e contado no log.
- Desconexão de um cliente SSE remove o assinante sem afetar o experimento.

## 9. Evolução prevista (não implementar agora)

- **Controle remoto pelo painel:** adicionar `POST /commands` ao `DashboardServer` e reaproveitar o parser de comandos do `cli`. O SSE continua como canal de saída.
- **Visão do servidor no painel:** o servidor publica seus eventos para o cliente por um canal de controle.

## 10. Testes

JUnit 5, com TDD em cada tarefa. O relógio e o `Random` são injetados para determinismo.

### 10.1 Unitários (sem rede)

- **`wire`:** ida e volta da codificação; leitura TCP com stream que entrega 1 byte por vez; rejeição acima de 64 KiB.
- **`impair`:** com semente fixa, `loss=0` entrega tudo, `loss=100` nada, e `loss=10` fica entre 8% e 12% em 10 000 amostras; atraso e reordenação com relógio controlado.
- **`metrics`:** RTT mín/média/p95/máx, perda %, reordenação e jitter (RFC 3550) contra valores calculados à mão.
- **`event`:** `toJson()` de cada record. O `EventBus` com assinante bloqueado descarta os mais antigos e `publish` não bloqueia.
- **`cli`:** parsing de `run`, `impair` (valores, presets, `off`) e de entradas inválidas.

### 10.2 Integração (loopback, portas efêmeras)

- O servidor real em `127.0.0.1` com porta 0.
- **`lifecycle`:** a sequência contém `ConnectionEstablished`, `ConnectionClosed(graceful=true)`, `ConnectionClosed(graceful=false)` e `ConnectionRefused`.
- **`ConnectionReset`:** um teste derruba o servidor durante uma conexão TCP aberta e confere que o cliente publica `ConnectionReset` ou `ConnectionClosed(graceful=true)` (o SO pode enviar FIN ou RST; ambos são aceitos).
- **`reliability`:** sem `impair`, 100% de echos; com `loss=100`, 100% de `PacketLost` no UDP; TCP com zero perdas.
- **`latency` / `throughput`:** rodadas curtas; checam a estrutura dos resultados, não valores absolutos.
- **`dashboard`:** um `HttpClient` do JDK lê `/events` e valida o formato SSE e o JSON.

### 10.3 CI

GitHub Actions: `mvn verify` em uma matriz `ubuntu-latest`, `macos-latest` e `windows-latest`, com Java 21 (Temurin).

### 10.4 Aceitação manual (README)

1. No Windows: liberar o Java no firewall e rodar `java -jar netlab.jar server`.
2. No Mac: rodar `java -jar netlab.jar client --target <ip-windows>` e anotar a URL do painel.
3. No iPhone: abrir a URL no Safari.
4. `run lifecycle` → a linha do tempo mostra handshake, FIN, RST, recusa e o UDP sem handshake; o console do Windows mostra o encerramento gracioso e o reset vistos pelo servidor.
5. `impair preset=wifi-ruim` e `run reliability` → TCP com 0 perdas; UDP com perda em torno de 10% (5% em cada sentido) e reordenações.
6. `run latency nagle=on` e depois `nagle=off` → diferença visível no RTT do TCP.
7. `impair off` e `run throughput udp-rate=500mbps` → perda UDP crescendo acima da capacidade; TCP se ajustando.
