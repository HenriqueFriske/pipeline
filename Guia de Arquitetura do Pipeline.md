# **Especificação Técnica e Manual de Engenharia: Pipeline de Coleta de Dados**

Este documento serve como especificação técnica detalhada para o desenvolvimento e implantação do pipeline automatizado de coleta de dados do experimento fatorial misto ![][image1]. Ele descreve a arquitetura do sistema, o fluxo de dados, a API de comunicação, os requisitos de resiliência e as estratégias de tratamento de erro de cada componente.

## **1\. Visão Geral do Sistema e Requisitos de Escopo**

O pipeline deve executar, monitorar e persistir os resultados de **750 rodadas experimentais** (30 trechos de código Java ![][image2] 5 níveis de Persona ![][image2] 5 réplicas). Cada rodada é uma unidade de execução atômica com o ciclo de vida ilustrado abaixo:

\[Isolamento de Código\]   
       │  
       ▼  
\[Geração de Código (Gemini API)\] ──(Falha na API / Timeout)──► \[Estado: FALHA\_API\]  
       │  
       ▼  
\[Extração Regex do Código Java\] ──(Nenhum bloco encontrado)─► \[Estado: FALHA\_EXTRACAO\]  
       │  
       ▼  
\[Compilação no Defects4J\] ────────(Erro de Sintaxe/Compilar)─► \[Estado: FALHA\_COMPILACAO\]  
       │  
       ▼  
\[Suíte de Testes Unitários\] ──────(Falha/Regressão/Timeout)──► \[Estado: FALHA\_TESTES\]  
       │  
       ▼  
\[Análise Estática (SonarQube)\] ────(Falha de Comunicação)─────► \[Estado: FALHA\_ANALISE\]  
       │  
       ▼  
\[Métricas Extraídas e Salvas\] ──────────────────────────────► \[Estado: SUCESSO\]

### **Critérios de Aceitação de Escopo:**

1. **Não-Poluição de Estado:** O pipeline deve garantir isolamento absoluto entre as rodadas. Nenhuma alteração de código ou cache de análise estática de uma réplica pode interferir na rodada subsequente.  
2. **Prevenção de Desperdício de Recursos:** Chamadas de API custam tokens e tempo. Se uma etapa de infraestrutura falhar (ex: SonarQube offline), o pipeline deve pausar imediatamente e salvar o progresso atual, em vez de queimar requisições da API do Gemini repetidamente.  
3. **Resiliência a Travamentos (Ledger de Checkpoint):** O orquestrador deve salvar o estado de cada execução em um arquivo de progresso em tempo real (checkpoint\_ledger.csv). Se o script Python for interrompido na rodada 412, a reinicialização do script deve ler o ledger e retomar exatamente da rodada 412, sem reexecutar as anteriores.

## **2\. Configuração de Infraestrutura e Dependências de Sistema**

O ambiente de execução local do programador deve atender rigorosamente aos seguintes pré-requisitos:

### **2.1 Ambientes de Execução Local**

* **Sistema Operacional:** Linux (Ubuntu 20.04 LTS ou superior recomendado) ou macOS. No Windows, deve ser executado obrigatoriamente via WSL2 (Ubuntu).  
* **Python v3.10 ou superior:** Necessário para suporte a tipagem forte (typing) e gerenciamento nativo de subprocessos assíncronos.  
* **Java Development Kit (JDK) v8:** O Defects4J requer **exclusivamente** o Java 8 (OpenJDK 8\) configurado no PATH e como JAVA\_HOME. Versões mais recentes do Java quebrarão a compilação de projetos antigos do benchmark.

### **2.2 Dependências Externas (Docker e CLI)**

* **Docker Engine v20.10+:** Utilizado para executar o servidor do SonarQube de forma limpa.  
* **SonarScanner CLI v5.0+:** Deve estar instalado localmente e mapeado no PATH do sistema para disparar as análises estáticas via terminal.  
* **Defects4J v2.0+:** Instalado localmente com todas as suas dependências Perl funcionais e o comando defects4j acessível globalmente.

## **3\. Arquitetura Detalhada de Módulos (Especificação do Código)**

O pipeline deve ser modularizado em arquivos Python independentes ou classes de responsabilidade única dentro do pacote do orquestrador.

pipeline/  
├── \_\_init\_\_.py  
├── main.py                \# Ponto de entrada do Orquestrador Principal  
├── core/  
│   ├── api\_client.py      \# Módulo 1: Conexão e Backoff da API Gemini  
│   ├── defects4j\_mgr.py   \# Módulo 2: Isolamento, Compilação e Testes  
│   ├── parser.py          \# Módulo 3: Regex Parser e Sanitização AST  
│   ├── sonarqube\_anal.py  \# Módulo 4: Automação do Scanner e API REST  
│   └── ledger.py          \# Módulo 5: Estado Persistente e Checkpoint

### **MÓDULO 1: Cliente de Integração LLM (api\_client.py)**

#### **Responsabilidade:**

Gerenciar a montagem dos prompts, cabeçalhos de requisição HTTP, parâmetros de geração de hiperparâmetros da LLM e executar a comunicação segura com a API do Gemini utilizando retry estruturado com backoff exponencial.

#### **Assinatura do Método Principal:**

def generate\_refactoring(  
    persona\_preamble: str,   
    base\_instruction: str,   
    code\_snippet: str,   
    temperature: float \= 0.2  
) \-\> str:  
    """  
    Realiza a requisição síncrona HTTP POST para a API do Gemini.  
    Aplica política rígida de 5 tentativas com backoff exponencial e tratamento de exceções.  
    """

#### **Definição das Personas (![][image3] a ![][image4]):**

O programador deve parametrizar os seguintes preâmbulos de sistema exatos em inglês para garantir o isolamento da variável identitária:

* ![][image3] **(Controle Negativo Adversário):** "You are an experienced artisan baker specializing in sourdough breads and traditional pastries. You have no knowledge of software engineering, programming, or computer science."  
* ![][image5] **(Neutro \- Sem Persona):** "" (String vazia. O prompt do sistema deve ser omitido, enviando apenas a instrução-base).  
* ![][image6] **(Genérico):** "You are a developer."  
* ![][image7] **(Especializado):** "You are a Senior Java Software Architect and Clean Code expert."  
* ![][image8] **(Contextual):** "You are a Senior Java Software Architect and Clean Code expert. You are fully contextualized within the project architecture, adhering to its specific design patterns, dependency structures, and development guidelines."

#### **Endpoint e Payload da API do Gemini:**

* **URL de Requisição:** POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-preview-09-2025:generateContent?key={API\_KEY}  
* **Headers Mandatórios:**  
  {  
    "Content-Type": "application/json"  
  }

* **Corpo da Requisição (Payload):**  
  {  
    "contents": \[  
      {  
        "parts": \[  
          {  
            "text": "\[Instrução-Base de Refatoração\]\\n\\n\[Código Fonte Java Original\]"  
          }  
        \]  
      }  
    \],  
    "systemInstruction": {  
      "parts": \[  
        {  
          "text": "\[Preamble de Persona\]"  
        }  
      \]  
    },  
    "generationConfig": {  
      "temperature": 0.2  
    }  
  }

#### **Lógica de Tratamento de Erros e Backoff Exponencial:**

A chamada deve capturar erros de timeout de rede, limite de requisições excedido (HTTP 429\) e erros internos do servidor (HTTP 5xx). O programador deve implementar um loop de retentativas seguindo o intervalo exato abaixo, **silenciando logs de erro intermediários no console** para evitar poluição visual:

* ![][image9]Tentativa 1: Esperar 1 segundo (+ jitter) antes de tentar novamente.  
* Tentativa 2: Esperar 2 segundos (+ jitter) antes de tentar novamente.  
* Tentativa 3: Esperar 4 segundos (+ jitter) antes de tentar novamente.  
* Tentativa 4: Esperar 8 segundos (+ jitter) antes de tentar novamente.  
* Tentativa 5: Esperar 16 segundos (+ jitter) antes de tentar novamente.  
* *Se todas as 5 tentativas falharem:* Levantar exceção fatal RuntimeError com descrição amigável de erro.

### **MÓDULO 2: Parser de Código e Sanitização (parser.py)**

#### **Responsabilidade:**

Isolar o código Java retornado pela LLM, garantindo que textos explicativos, introduções ("Here is your refactored code") ou blocos markdown quebrados não contaminem o arquivo fonte java físico do projeto.

#### **Regex de Extração de Bloco Multilinhas:**

O parser deve executar uma busca de expressão regular insensível a maiúsculas/minúsculas para capturar blocos Java válidos:

import re

def extract\_java\_block(llm\_response: str) \-\> str:  
    \# Captura tudo entre as tags de código do markdown \`\`\`java ... \`\`\`  
    pattern \= r"\`\`\`java\\s\*(\[\\s\\S\]\*?)\\s\*\`\`\`"  
    match \= re.search(pattern, llm\_response, re.IGNORECASE)  
    if match:  
        return match.group(1).strip()  
    return ""

#### **Regras de Tratamento de Erros de Extração:**

1. **Nenhum Bloco Markdown Encontrado:** Se a regex retornar vazia, o parser deve tentar uma varredura de fallback procurando por assinaturas de classe ou método Java comuns (ex: public class, private void) na resposta inteira. Se ainda assim falhar, levanta um erro de status FALHA\_EXTRACAO.  
2. **Códigos Incompletos ou Truncados:** Se a resposta terminar abruptamente devido ao estouro de limites de tokens de saída, o parser deve identificar o não fechamento de chaves {} e sinalizar FALHA\_EXTRACAO para evitar tentar compilar um código sabidamente quebrado.

### **MÓDULO 3: Gerenciador de Workspace Defects4J (defects4j\_mgr.py)**

#### **Responsabilidade:**

Garantir o isolamento do código de testes, realizar o checkout limpo do repositório, substituir o arquivo Java modificado, compilar o projeto e rodar a suíte completa de testes.

#### **Comandos de Linha de Comando Executados via subprocess:**

\# 1\. Checkout Limpo do Projeto em um Diretório Isolado  
\# Ex: defects4j checkout \-p Math \-v 10f \-w /tmp/workspace/math\_10\_f  
defects4j checkout \-p \<PROJECT\_ID\> \-v \<VERSION\_ID\>f \-w \<WORKSPACE\_PATH\>

\# 2\. Compilação do Código Modificado  
defects4j compile

\# 3\. Execução da Suíte Completa de Testes  
defects4j test

#### **Regras de Tratamento de Erros e Controle de Processos:**

* **Timeout de Testes:** A execução de defects4j test deve ser controlada obrigatoriamente por um parâmetro de timeout limite de **180 segundos**. Se o processo expirar, o orquestrador deve matar o processo filho e todas as suas ramificações em nível de sistema operacional (usando os.killpg) para evitar processos zumbis que consomem CPU infinita (loops infinitos gerados pela LLM). A rodada atual deve receber o status FALHA\_TESTES\_TIMEOUT.  
* **Verificação de Regressão Comportamental:** O comando defects4j test retorna 0 apenas se todos os testes unitários passarem. Se houver falha de comportamento (teste quebrado), o comando retornará um código diferente de zero e listará os testes falhos no console. O log de saída deve ser capturado e a rodada marcada como FALHA\_TESTES\_COMPORTAMENTO.

### **MÓDULO 4: Automação e API REST do SonarQube (sonarqube\_anal.py)**

#### **Responsabilidade:**

Iniciar o escaneamento estático local no arquivo refatorado e consumir as APIs REST internas do servidor SonarQube para colher as métricas estruturais.

#### **Chamada do SonarScanner CLI:**

sonar-scanner \\  
  \-Dsonar.projectKey=\<CHAVE\_UNICA\_DA\_RODADA\> \\  
  \-Dsonar.projectName=\<NOME\_DO\_TRECHO\> \\  
  \-Dsonar.sources=\<CAMINHO\_DO\_ARQUIVO\_JAVA\> \\  
  \-Dsonar.host.url=http://localhost:9000 \\  
  \-Dsonar.token=\<SONAR\_USER\_TOKEN\> \\  
  \-Dsonar.java.binaries=\<CAMINHO\_DOS\_BYTECODES\_COMPILADOS\>

#### **Consulta Assíncrona de Processamento (Sonar Task Queue):**

Após o comando sonar-scanner terminar, ele gera uma tarefa assíncrona no servidor SonarQube. O script Python **não pode** ler as métricas imediatamente; ele deve monitorar a fila de processamento até que o status da análise mude para SUCCESS.

* **API de Status da Task:** GET http://localhost:9000/api/ce/activity?component=\<CHAVE\_UNICA\_DA\_RODADA\>  
* **Payload de Resposta Esperado:**  
  {  
    "tasks": \[  
      {  
        "status": "SUCCESS", // Pode ser: PENDING, IN\_PROGRESS, SUCCESS, FAILED  
        "type": "REPORT"  
      }  
    \]  
  }

* **Ação:** O script deve consultar este endpoint a cada 2 segundos, com um timeout limite de 30 segundos. Se o status for FAILED, abortar com FALHA\_ANALISE.

#### **Coleta das Métricas Estruturais:**

Uma vez confirmada a conclusão da tarefa de análise, o script deve invocar o endpoint de medidas de componente para buscar a Complexidade Ciclomática e a Contagem de Code Smells.

* **API de Medidas:** GET http://localhost:9000/api/measures/component?component=\<CHAVE\_UNICA\_DA\_RODADA\>\&metricKeys=complexity,code\_smells  
* **Headers de Autenticação:** Usar autenticação básica (Basic Auth) enviando o Token do SonarQube no campo de usuário e senha em branco.  
* **Payload de Resposta e Parsing de Métricas:**  
  {  
    "component": {  
      "key": "Math\_10\_ExtractMethod\_P2\_R1",  
      "name": "Math\_10\_ExtractMethod",  
      "measures": \[  
        {  
          "metric": "complexity",  
          "value": "12",  
          "bestValue": false  
        },  
        {  
          "metric": "code\_smells",  
          "value": "3",  
          "bestValue": false  
        }  
      \]  
    }  
  }

### **MÓDULO 5: Gerenciador de Ledger e Persistência (ledger.py)**

#### **Responsabilidade:**

Garantir o checkpoint em tempo real e a resiliência do progresso, gravando os dados estruturados de cada rodada imediatamente após sua conclusão.

#### **Estrutura do Arquivo Ledger (checkpoint\_ledger.csv):**

O arquivo CSV de progresso deve ser escrito de forma incremental usando escrita direta em disco (flush ativo em cada append) para prevenir corrupção caso o computador perca energia.

id\_rodada,trecho,refatoracao\_tipo,persona,replica,status,complexity\_base,complexity\_pos,complexity\_delta,smells\_base,smells\_pos,smells\_delta,tempo\_execucao\_seg,token\_count  
1,Math\_10\_ExtractMethod,ExtractMethod,P-1,1,VALIDO,18,12,-6,8,3,-5,24.5,1250  
2,Math\_10\_ExtractMethod,ExtractMethod,P-1,2,FALHA\_COMPILACAO,,,0,,,0,4.2,1180

## **4\. Matriz Completa de Tratamento de Erros e Exceções**

O programador deve mapear cada possível falha para uma ação de recuperação graciosa descrita abaixo:

| Cenário de Falha | Módulo Impactado | Ação de Recuperação do Pipeline | Código de Status no Ledger |
| :---- | :---- | :---- | :---- |
| **Timeout de Conexão na API Gemini** | Módulo 1 | Iniciar backoff exponencial. Se falhar após as 5 tentativas, pular para a próxima réplica. | FALHA\_API\_TIMEOUT |
| **Limite de Requisições Excedido (HTTP 429\)** | Módulo 1 | Pausar execução por 30 segundos adicionais além do backoff padrão e tentar novamente. | FALHA\_API\_RATE\_LIMIT |
| **Resposta sem Markdown Java válido** | Módulo 2 | Tentar extrair por análise heurística de chaves {}. Se falhar, descartar e pular. | FALHA\_EXTRACAO |
| **Código Java com Erro de Sintaxe** | Módulo 3 | Capturar os logs do stdout de compilação do Defects4J e gravar o motivo do erro no log local. | FALHA\_COMPILACAO |
| **Loop Infinito gerado no código refatorado** | Módulo 3 | Interromper o processo de testes unitários ao estourar o timeout de 180s. Forçar kill de processos filhos. | FALHA\_TESTES\_TIMEOUT |
| **Queda do Servidor do SonarQube** | Módulo 4 | Pausar imediatamente todo o pipeline por tempo indeterminado e emitir um aviso sonoro/terminal para intervenção humana. Não tentar consumir a LLM se o servidor de análise estiver fora do ar. | PAUSE\_SYSTEM\_ERROR |

## **5\. Diretrizes Finais de Implementação para o Desenvolvedor**

1. **Parâmetro de Semente Fixo:** Como o experimento usa um modelo Gemini em uma versão de visualização (gemini-2.5-flash-preview-09-2025), as configurações de temperatura devem estar travadas estritamente em 0.2 para mitigar o não-determinismo sem castrar a flexibilidade de refatoração do modelo.  
2. **Registro de Logs de Depuração:** Implemente logs detalhados em arquivos separados por módulo (logs/api.log, logs/defects4j.log, logs/pipeline.log). Apenas informações consolidadas de status e progresso de porcentagem devem ser impressas no console principal do terminal.  
3. **Gerenciamento de Cache do SonarQube:** Lembre-se de deletar ou resetar os projetos criados temporariamente no SonarQube (GET /api/projects/delete) ao final de cada 30 rodadas para evitar saturação do banco de dados do contêiner Docker.

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAC4AAAAZCAYAAABOxhwiAAACbElEQVR4XsWWv27UQBDGz0I0IEAg0El3zvr+SYeQqO4FSJ8noOEVACkFIoECaCgpkKBBSJGAmgqJdwDKtElDkwJCAiEg+GY9m1sPs/b6ZIdfNGd75pvZ2Tnbl07nmEi8TxUXkhJ5HUWomITiikZx5T4tsChN1mq2WkStoiQioRVqrDsYDG7B3hhjrvD15SzLNnC8KbVtg3Xfw/7AdrH+DRkvANFDEps8wdkHqWsbrHmAwwk6n06nZ2xPxmwJ2RwE70P0BDt8ieNah5OrqfG1HqHnoId7PLB1J3FDLCo9qFlMe1kvWQ5yrwd6scxms5M0PemXpGl6lSe84nzljSdWcBe2LEMxDIfDLnK/SD/R7XZPI/YzNOUquPFdOlcqJPQw3oHgUX6PmxeUAN8zqQzR7/dT5HyzF7wCN33oyeJA/mg0OkfDgO3LcAEIbuMreid8tNsHvq+M8Xi8hI3v0Tk3/cuPKxNTQd467DnsO+wp+WJzLdx44P7SoeaRsyebLiOxfzoxPSTF5ISSflclSdKl9AJyDmlaMhaF2AHqvOXmXxUjDAd3FF9043gr2KbpvNfrXcyq7k8B9B9hX4VvjfvQnxUOriq+qMaNyc7Pi+dj4+btPR/CH7BbD3mnPN8G+fD8vfakcxDcn0wml9w13ijXKAGvuqmv00DeWWh/SD9BzRv3tqkANbZhj4WvengQ7FihyX/6scuR1GhU/T9DPz60AenXwPqfuNlNPn6Wmn8IPdkFFBG5FHcpdfXHQLilcESnrr4aV7GJyk3UaBPxI9EAjRdkGqkbWSRS1qmjbIWj5RfsY8G0/0V8u/HKxfgLPt2RrtDL894AAAAASUVORK5CYII=>

[image2]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAZCAYAAAA4/K6pAAABAklEQVR4XrVSywrCMBBsxAeIerIHC2kPFgq99iv8CP//HxQf2Tzq7uaJ6IC4mZ2ZTZNUoipBTjX3Y0LDe12PKIDApljtCM7x9VcgOzBMoPZEleEIL9DSVpiiSCVbdF13Un+LlEhpLpwjUIKX8i8x5+JU79Y0zfbDRQZByDRNK8bd67reQa0/M+KdgUPAPAzDPmvigBAw931/0AQLiB84QOiAJ/xUwIa347CvBIz6m23QOI7r1DwCbT6aA8OcOxPhP0VyVQ93VRjggRBVmiumGWYlpTzDVkkLAVRt2145XwQ3MLD5H8NMCM1J7MF7EAFNCIWyfyE4PkgyaE1OCK8mo8HtN1b9HiearPSvAAAAAElFTkSuQmCC>

[image3]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACEAAAAaCAYAAAA5WTUBAAABg0lEQVR4XrWVPU7EMBCFsyWipEiRCCenoKaipaSm4RCUXACEuAQNPRfgAEhISByAAvEjhEAUwDOZbOzJeO1xsp802t2Z55lnZyMXRSYLnlgLkSluOSKdwKjzKKFCXi1nixWFDuEIIisGUoRN05waY94QvxSfiGc3B80lX5dCaH4oX/QDuaRtW0NGrr3CPPjDyMSNlyQGgwVfJiWIRbAigl0e0G73eK2u6w3PhAKVCQy4Cw1B/ooM7vParPg7Hfwjt0sGzpbJGegmsHPqTSBeES+IL/p9W1XVlq+2qA6aIazt/w+IQ17LpSzLTbshng8C8b01wfO5oNcFNrbj9xR270KnEDARXjyqUKL/yDHxwPN5LIcxExKkhfDYinF8R75Ax7BPhQkIzhHvpnsT7D3xgfjhOhdjtu2dQuF+/49HJo+bUBN5pAKyCa9PpGmknIJsYhIKVxj+TY/4iR73CdckoJiYzDp6BlFe3xbh1cpiQh/1gtWktEvRpIlSmbWZRGRApGz5AwhCZP7e4CWZAAAAAElFTkSuQmCC>

[image4]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABkAAAAfCAYAAAASsGZ+AAABp0lEQVR4XtWUv0oEMRDGb0try132T6eV+AhWllpYWNvY+ASKjU+g+BYiiK214AMIB6I+gI1/kVNEz29MciZzmSS73hX+YHAz8+WbSXbPXm+SZDxhYxXN4yiVunGEL/dfCZ6lruv9qqqeEEMdA8S9nYPmiO/rhDHk+aZpKt3oTGXcme0VPQdPpJqUFzxPSAM4OO6eVphyXU+7zGtFUcwkNfnBY25yMOhLJsif6AFWvR6pSJMit6QbHPBaFD4QGZWq0SPiAfGG90PryzzPZ5ncgXt5Me8DscFrNqgvIj4Rd9iz4hRjLx2brqgJz3OgGVjPQ5ywsOtB9Cm8TdRM6uuH5tnkSY/fz4JZO3gOYprc8LxEWZZr0F/zvAjEO9QEd7zJaz6g3aPrhX6O18aA8BBfz0ulviT6P/WK+OI6CX36LZ6P4rtKAwznEefWmpq825oopoHUCIa7iA9rTU22bY2DZBQDpn28i1P8vUUc/1a6OgbobJm0MUkURDuEjEK16TDVjuPmWcIltEdwy5yCIJoEf7YmA2XSycqztZVPK3Eq+kydvKVNUj7CN/SuZcndEZgiAAAAAElFTkSuQmCC>

[image5]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABUAAAAbCAYAAACTHcTmAAABvElEQVR4XsVTu0rEQBTdtRHUNq4GMhMxjf/gLwg2VnY2YqFYCpYWfoCgguBWfoWNuI2iQQstBDtBREQLWRCVXc8kM8mdh5mAqAfuZuecc8/cyaPR8KBpEj+HJ9KSDcLSLTgcDupvwDmfR52hLlCXslLUCeoojuM15S2H9I0rdcZ5HyF9/B1QfBRFk+AeBY8az6zljz9bNn4UBGnINSY29EP1YaJQNDLGNjWDBGfFKQpUD9nMJtkWTUEQjJgiNpqTp0gpTy9OiKOZkyiA732nVUJO8pav8u3x1Gdl4D2xFqh8E9A8Jie9Zpy1cd3HkXfAr5pegTAMh0zOAgJ25aQTpkYBvYOaSpJkENfPUnFMCsONCHVIGugrBf/TaKs1nK8cnXLKygeB08zAc6fW+L8Brk09GmTorclTQD9ApWomBK5g3VW6NiuEdRGKh7JIeQrRAM8hgk4VB/+S+KyJLTNtoV5RL6hn3K8uTD2lm3cJnj3UuVpjg2Ws36mngNlsEznwmS5gY+2eYtpj6vHAnWw8/SuExkQu4W53A0eeRtgDqiM+DqrZOTajIZM9nhqQCZ4gj2yjdkNt47/jtyf9AnAlamxZldlDAAAAAElFTkSuQmCC>

[image6]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABUAAAAbCAYAAACTHcTmAAABeklEQVR4Xr2Tv0oEMRDG76xtF/Rgkyw+ieAD2FhZi62l4AMItoJa6atYCnKcjYVgbSGigojCwa1fklnNn0n2grA/GHZ35psvyVxuNEoxDhMlmOZSB9KXtiX5v1G5g9NhX6WUu1KKWzxniDuKKeIGca2UOvQ7CoBBK4Vs8brS5eq63kD+2dSkWHfkluQqVCDTuV+0WFPZpjy8fPeBHU10kxDi2K07IzKmfi1BZ4qjnemmqqpWPQHAQjtkOjWJ6DoG++/q6Z2MdW3B13og0283pxq1TYZPbt4jNWRcmTUyvcdRr/C8xPMcpge/IqdZ6/++GLQWBheYqTZtwrqLXgwxX2oUED30Cu3cT7CBvV6tho6eFLpjg24z1jKDJdPHMM9hTO2/zkJ+ni1ER9pUqWbfzUdQF79TAoVT/DgfeL4hXhGfiEWoC4lMmZPnYRoiUw6mL8tSpim4xWD2RaN6Qbzjem3ZCqc2MAUmlSfb4N+TrHRYzFYG3s/Ay/n8AFBkYO01UVEEAAAAAElFTkSuQmCC>

[image7]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABUAAAAbCAYAAACTHcTmAAABtklEQVR4Xs1UPUsDQRDNCVbaWBzRcLe7wU6wyh8RxEoQrexErAR/gI2doBai/8DfYClo0MZCsLYQVCSIopD4ZjOX29uvSEjhgyF77828mf0gtdqISGyCEeLHh2iHqDhGOH0cgiGlXEVcCylv8XvH0UZcIS6bSu3aNYTSLwl6k3lPCtlD0kTB5Xk+D/5Za1LOmfkarluV4cKfCsk5rPVcD4KfxURZg4qEEPu2RqAdkF4yASMTKDiiojRNpx1NiBWetK2JP/hpFNurkFwMvutoFvqpVjc2/TJ5pdQSGz6VbInowCieJVM8qXuc6TnWZ9jysWqqbTu3Xq9PtVqtSZt3AKMTnrTpti8JKcU7nlgDl3aKdfQ46GYfhp2ZkGIGRsvFN+VnWbboDsHgKeOmQqyZOVh/Im6GmT7afAy0fdQc9L/cW98jU1zWZlUJA1MvDKY2/UAeIjqIN8QrOn/gt2s3raIvIu/bEkIw3XjtaQDDl2KNJ3hhap66yN8XIdGGHRzTOmIDR7BFR2enaUSNNAZb3qFzFPxSKPSTchG21EpYjmG0qv+F0B78vJ+14HleHoQVQlx1Ecj/BVkJZ6TEjaP9AAAAAElFTkSuQmCC>

[image8]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABUAAAAbCAYAAACTHcTmAAABo0lEQVR4XrVUvS4FQRjdq1ZoNsHamd1oqLQeQ0HlATQKpZDbaDyABBUvoPEGCoVEbmhIBK3iFiREEMldZ+bObma++VtunOTL7J5zvvPNzt67SWKhQwkTEXkEdP4x+68YeUec81XUJeoKdS2Kcd7DeoE6K4pik/Y0iE1HUIWQCpdjNZfn+Sy4vuBRU5q9HVTjt2u80ipbCQA7mhZNjLFdcU+bjVAq+oCGfdGUpuk41TBoRYX2qBYcUO+E8gLgBz4tCBX6qU/GW19SgU8159uYxaN5UoXe4FGPsR5hPSjKYsPhnaCcEwg4VKEl1fQ9QO+LnxiGLUaPA4a7qCmRvg/tusqybKaeaT2+2mWb0Ne6mXNWFWW5YBh0cCZDHyhvjx9SOK5l+O+p1gDittglXsAa1QyoAfDuiOOCf840DMU91Bv+7y9Yn1HvqAH1+b6h6sjWKS/hanABAfOoc+1ehH7pHidCAxDQ5fJjU98zEbqlexqEgigQcouzPMX6iDqRpBHwmzQnAgGWZBEmpOz1hNUw2ve2MkVcvmkOSkdEFnBYHJQP1PoDlRxmguvS60IAAAAASUVORK5CYII=>

[image9]: <data:image/png;base64,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>