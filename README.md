# netlab

Laboratório de TCP/UDP em tempo real, em Java 21. Um **servidor** (eco TCP/UDP) roda em uma máquina e um **cliente** com prompt interativo roda em outra, na mesma rede local. O cliente executa experimentos e publica um painel web, que você abre no Safari do iPhone para ver tudo acontecendo ao vivo.

Três focos de aprendizado:

1. **Conexão e ciclo de vida:** handshake de 3 vias, FIN, RST, porta fechada, e o contraste com o UDP (sem conexão).
2. **Confiabilidade versus perda:** TCP entrega tudo e em ordem; UDP perde e reordena numa rede ruim (simulada).
3. **Desempenho:** RTT, jitter, vazão, efeito do algoritmo de Nagle e comportamento sob saturação.

## Topologia

| Máquina | Papel | O que roda |
|---|---|---|
| Desktop Windows | servidor | `server` (TCP 7000, UDP 7001) |
| MacBook | cliente | `client` (prompt + painel na porta 8080) |
| iPhone | visualizador | Safari abrindo o painel do Mac |

## Requisitos

- JDK 21 nas duas máquinas (Windows e Mac). O mesmo `netlab.jar` roda sem alteração nos dois sistemas.
- Maven, apenas na máquina onde você compila.
- Windows, Mac e iPhone na mesma rede Wi-Fi/LAN.

## Build

```bash
mvn package
```

Gera `target/netlab.jar`. Copie o JAR para o Windows (ou compile lá também).

## Uso

```
java -jar netlab.jar server [--tcp-port 7000] [--udp-port 7001]
java -jar netlab.jar client --target <ip> [--tcp-port 7000] [--udp-port 7001] [--http-port 8080]
```

| Flag | Modo | Padrão | Significado |
|---|---|---|---|
| `--target <ip>` | client | (obrigatória) | IP do servidor |
| `--tcp-port` | ambos | 7000 | porta TCP do servidor |
| `--udp-port` | ambos | 7001 | porta UDP do servidor |
| `--http-port` | client | 8080 | porta do painel |

As portas TCP/UDP precisam ser iguais nos dois lados. Sem argumentos (ou com argumentos inválidos), o programa imprime o texto de uso acima.

### Aviso: porta 7000 no macOS

No macOS, a porta TCP 7000 é usada pelo AirPlay Receiver ("Receptor AirPlay"). Isso só importa se você rodar o `server` no Mac. Nesse caso, use portas alternativas **nos dois lados**, por exemplo:

```bash
java -jar netlab.jar server --tcp-port 17000 --udp-port 17001
java -jar netlab.jar client --target <ip> --tcp-port 17000 --udp-port 17001
```

ou desative o AirPlay Receiver em Ajustes do Sistema > Geral > AirDrop e Handoff.

### Firewall do Windows (servidor)

Libere o `java.exe` em **redes privadas** para TCP 7000 e UDP 7001.

Pela interface: Segurança do Windows > Firewall e proteção de rede > Permitir um aplicativo pelo firewall > adicione o `java.exe` do JDK 21 e marque **Privada**.

Ou, em um PowerShell como administrador (ajuste o caminho do `java.exe`):

```powershell
New-NetFirewallRule -DisplayName "netlab TCP 7000" -Direction Inbound -Protocol TCP -LocalPort 7000 -Program "C:\caminho\para\jdk-21\bin\java.exe" -Profile Private -Action Allow
New-NetFirewallRule -DisplayName "netlab UDP 7001" -Direction Inbound -Protocol UDP -LocalPort 7001 -Program "C:\caminho\para\jdk-21\bin\java.exe" -Profile Private -Action Allow
```

Descubra o IP do Windows com `ipconfig` (endereço IPv4 da rede Wi-Fi/Ethernet).

### Aviso do macOS (cliente)

Na primeira execução do `client`, o macOS pode perguntar se o Java pode aceitar conexões de entrada. **Permita**; caso contrário o iPhone não consegue acessar a porta 8080 do painel.

### Painel

Ao iniciar, o cliente imprime:

```
Painel: http://<ip>:<porta>
```

Abra essa URL no Safari do iPhone (mesma rede Wi-Fi). O painel continua no ar entre uma execução e outra.

## Comandos do prompt

O cliente abre o prompt `netlab>`.

| Comando | Efeito |
|---|---|
| `run <experimento> [chave=valor ...]` | inicia um experimento (um por vez) |
| `impair loss=<0-100> delay=<ms> jitter=<ms> reorder=<0-100>` | piora o UDP do cliente; campos ausentes valem 0; valores por sentido |
| `impair preset=<wifi-ruim\|3g>` | aplica um perfil pronto |
| `impair off` | desliga o impair |
| `stop` | interrompe o experimento em andamento |
| `help` | mostra a ajuda |
| `quit` | encerra o cliente |

Ctrl+C durante um `run` aborta o experimento; Ctrl+C com nada rodando encerra o cliente. Parâmetros desconhecidos em `run` são rejeitados (um erro de digitação não cai silenciosamente no padrão).

### Experimentos

| Experimento | Parâmetros (padrão) | O que faz |
|---|---|---|
| `lifecycle` | `closed-port=7999` | handshake, FIN, RST, porta fechada (TCP e UDP) |
| `reliability` | `count=1000` `rate=200` | mensagens numeradas por TCP e depois UDP: entregues, perdidas, reordenadas (`rate` em msg/s) |
| `latency` | `samples=200` `nagle=off` `size=64` | ping-pong TCP e UDP: RTT e jitter; `nagle=on` desliga o `TCP_NODELAY` |
| `throughput` | `seconds=10` `udp-rate=20mbps` `size=1200` | TCP contínuo e UDP a taxa fixa: vazão e perda acima da capacidade |

### Perfis de `impair`

| Perfil | loss | delay | jitter | reorder |
|---|---|---|---|---|
| `wifi-ruim` | 5% | 30 ms | 20 ms | 3% |
| `3g` | 2% | 150 ms | 60 ms | 1% |

O `impair` vale apenas para o UDP do cliente e fica ativo até `impair off`. Os valores são **por sentido**: perda, atraso e reordenação são aplicados uma vez no envio e outra na recepção, então numa ida e volta o efeito é cerca de 2× (com `loss=5`, um echo se perde em ~10% das vezes; com `delay=30`, o RTT sobe ~60 ms). Não há simulação de perda no TCP; o painel mostra o zero de perdas do TCP como contraste.

## Roteiro de aceitação manual

| # | Passo | Resultado esperado |
|---|---|---|
| 1 | No Windows: liberar o Java no firewall e rodar `java -jar netlab.jar server`. | O servidor fica escutando em TCP 7000 e UDP 7001. |
| 2 | No Mac: rodar `java -jar netlab.jar client --target <ip-windows>` e anotar a URL do painel. | O prompt `netlab>` aparece e a linha `Painel: http://<ip>:8080` é impressa. |
| 3 | No iPhone: abrir a URL no Safari. | O painel carrega e mostra o estado da conexão com o servidor. |
| 4 | `run lifecycle` | A linha do tempo mostra handshake, FIN, RST, a porta fechada e o UDP sem handshake; o console do Windows mostra o encerramento gracioso e o reset vistos pelo servidor. Com o Firewall do Windows ativo, a porta 7999 costuma aparecer como **tempo esgotado** (ConnectTimeout após 3 s, e silêncio no UDP) em vez de recusa: veja "Porta fechada versus porta filtrada" abaixo. |
| 5 | `impair preset=wifi-ruim` e `run reliability` | TCP com 0 perdas; UDP com perda em torno de 10% (5% em cada sentido) e reordenações. |
| 6 | `run latency nagle=on` e depois `nagle=off` | Diferença visível no RTT do TCP. |
| 7 | `impair off` e `run throughput udp-rate=500mbps` | Perda UDP crescendo acima da capacidade; TCP se ajustando. |

## O que observar

**Handshake de 3 vias.** Antes de qualquer dado, o TCP troca SYN, SYN-ACK e ACK. O tempo de `connect()` é, na prática, um RTT; no `lifecycle` ele aparece como tempo de handshake. O UDP não tem esse passo: o primeiro datagrama já carrega dados.

**FIN versus RST.** Um fechamento normal envia FIN: o servidor lê fim de stream (EOF) e encerra graciosamente. Com `setSoLinger(true, 0)` o `close()` aborta a conexão com RST: o servidor vê "connection reset" em vez de EOF. As duas formas aparecem de modo diferente no console do servidor.

**Porta fechada.** No TCP, o SO responde com RST e o `connect()` falha na hora ("conexão recusada"). No UDP não há resposta garantida: ou há silêncio (timeout), ou chega um ICMP port unreachable, que um `DatagramSocket` conectado transforma em `PortUnreachableException`.

**Porta fechada versus porta filtrada.** Uma porta *fechada* responde: o SO devolve RST ao SYN e a recusa é imediata. Uma porta *filtrada* não responde: o firewall descarta o SYN em silêncio, o cliente retransmite até desistir e o `connect()` termina em `ConnectTimeout` após 3 s; no UDP, o firewall também engole o datagrama (nada de ICMP), e o resultado é silêncio. O Firewall do Windows descarta por padrão o tráfego para portas sem regra, por isso a porta 7999 do `lifecycle` normalmente aparece como filtrada. Para ver uma recusa de verdade, crie temporariamente uma regra que permita a 7999 (sem nada escutando nela, o Windows passa a responder com RST) e remova-a depois:

```powershell
New-NetFirewallRule -DisplayName "netlab 7999 (temporária)" -Direction Inbound -Protocol TCP -LocalPort 7999 -Profile Private -Action Allow
Remove-NetFirewallRule -DisplayName "netlab 7999 (temporária)"
```

Outra opção é rodar o `server` no próprio Mac (com portas alternativas, veja o aviso da porta 7000) e o `client` com `--target 127.0.0.1`: sem firewall no caminho, a 7999 é recusada na hora.

**Nagle e ACK atrasado.** O algoritmo de Nagle segura um segmento pequeno enquanto houver dado enviado sem ACK. No padrão write-write-read (duas escritas pequenas seguidas de uma leitura), a segunda escrita espera o ACK da primeira, e o receptor pode atrasar esse ACK (ACK atrasado, algumas dezenas de ms ou mais). O resultado é um RTT bem maior com `nagle=on` do que com `nagle=off` (`TCP_NODELAY`). Quanto o efeito aparece depende do sistema operacional.

**Controle de congestionamento.** O TCP reduz a taxa ao detectar perda ou fila cheia (janela de congestionamento) e retransmite, então a vazão se ajusta à capacidade do caminho. O UDP não tem controle nenhum: acima da capacidade, o excedente é simplesmente descartado, e a perda cresce com a taxa em `throughput udp-rate=500mbps`.

**Perda e reordenação simuladas só no UDP.** O `impair` embaralha apenas datagramas UDP. O TCP esconde perdas e reordenação reais da aplicação (retransmite e reordena), por isso mostra 0 perdidas e 0 fora de ordem, enquanto o UDP entrega o que a rede (ou o `impair`) deixar passar.
