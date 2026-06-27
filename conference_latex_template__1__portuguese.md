Persona Prompting em Refatoração de Código: Um Efeito Placebo? 

Uma Proposta de Estudo Experimental Fatorial 

Gabriel Just1, Henrique Friske1, Felipe Pisoni1, Renan Fischer1 

1*Programa de Engenharia de Software, Universidade Regional do Noroeste do Estado do Rio Grande do Sul (UNIJUÍ), Ijuí, Brasil* 

ABSTRACT 

The adoption of Large Language Models (LLMs) in soft ware development has popularized persona prompting, the practice of assigning an identity such as “act as a senior developer” to improve generated code. Empirical evi dence, however, shows that personas yield null or incon sistent gains in question-answering and reasoning tasks, and their effect on code refactoring remains untested. This work proposes a controlled experiment to measure how per sona specialization affects the structural quality of refac tored Java code. It follows a 5 *×* 3 mixed factorial de sign that crosses five persona levels, ranging from an out of-domain negative control to a project-contextual persona, with three refactoring tasks of increasing complexity (Ex tract Method, Replace Magic Number, and Replace Con ditional with Polymorphism). Persona is a within-subject factor and refactoring type is a between-subject factor, with ten snippets per refactoring type. The five persona levels are applied to 30 Java code snippets from the Defects4J benchmark under five replications, for a total of 750 ob servations. Behavior preservation is checked through the benchmark unit-test suites and serves as a validity crite rion, so that only behavior-preserving refactorings reach the analysis. Structural quality is then measured by McCabe cyclomatic complexity and SonarQube code smells, and as sessed with the Friedman test and Nemenyi post-hoc anal ysis; the persona-by-task interaction is explored through Aligned Rank Transform ANOVA, and an equivalence test (TOST) supports the placebo interpretation. By isolating the persona variable, the study determines whether persona prompting yields measurable quality gains in refactoring or amounts to a statistical placebo effect, offering empirical guidelines for prompt engineering in software maintenance. 

I. INTRODUÇÃO 

A manutenção é a fase mais cara do ciclo de vida de um software, e a refatoração desempenha nela um papel cen tral, pois reestruturar o código internamente, sem alterar o comportamento observável, mantém a dívida técnica sob controle \[1\]. Nos últimos anos, essa tarefa deixou de ser apenas humana. A adoção de Grandes Modelos de Lin guagem (*Large Language Models*, LLMs) no desenvolvi mento avançou rapidamente. Em 2024, 62% dos desen volvedores já utilizavam assistentes de inteligência artifi 

cial no dia a dia, contra 44% em 2023, e 76% os utilizavam ou pretendem utilizar \[7\]. Boa parte do esforço rotineiro de manutenção, a refatoração inclusive, passou a esses assis tentes. 

Para guiar o que esses modelos produzem, a engenharia de prompts firmou-se como prática, e entre seus padrões reutilizáveis está a atribuição de personas \[8\]. A técnica consiste em instruir o modelo a assumir uma identidade, como “Aja como um arquiteto de software sênior especial ista em Clean Code”, na expectativa de que o papel melhore o estilo e a precisão do código \[8\]. Convém ler esse com portamento como interpretação de papéis (*role play*), e não como sinal de capacidades cognitivas reais do modelo, para não incorrer em antropomorfismo \[9\]. 

A heurística é popular, mas sua eficácia em tarefas téc nicas restritas tem pouco respaldo empírico. Em pergunta resposta e em raciocínio, atribuir personas não melhora o desempenho de forma consistente, e o resultado de cada papel aproxima-se do aleatório \[10\]. Modelos grandes tam bém se mostram sensíveis a atributos identitários sem re lação com a tarefa, e perdem acurácia quando o contexto da persona é incongruente \[11\]. Para refatoração, porém, não existe evidência que confirme ou descarte o ganho de qualidade que se costuma atribuir às personas. Diante desse cenário, o problema de pesquisa abordado neste estudo reside na ausência de evidências empíricas que compro vem se a atribuição de personas altamente especializadas a LLMs gera ganhos mensuráveis na qualidade do código refatorado ou se constitui apenas um efeito placebo, sem impacto estatístico significativo. Na ausência dessa evidên cia, engenheiros de software despendem tokens de API e esforço cognitivo na elaboração de prompts artificialmente complexos, apoiados em impressões anedóticas e não em dados. 

Este estudo investiga o efeito do nível de especialização da persona sobre a qualidade da refatoração. A refatoração é o objeto manipulado no experimento, e a qualidade es trutural do código resultante é a variável dependente que mede o êxito da tarefa. A corretude comportamental, isto é, a preservação do comportamento observável que define toda refatoração \[1\], não entra como objeto de pesquisa, mas como critério de validade, de modo que apenas as refatorações que preservam o comportamento, confirmadas pela suíte de testes do benchmark, seguem para a análise de qualidade. 

Dessa forma, para isolar o efeito da persona, adota-se um experimento controlado com desenho fatorial misto 5 *×* 3, 

1  
em que a persona é fator intra-sujeito e o tipo de refatoração é fator entre-sujeitos, cruzando os cinco níveis de especial ização da persona com três tipos de refatoração de com plexidade crescente. Os objetos são trechos de código Java do benchmark Defects4J \[5\]; a suíte de testes do benchmark verifica a preservação do comportamento, e a qualidade es trutural, variável dependente, é medida por análise estática. 

Com isso, este trabalho apresenta três contribuições prin cipais. Primeiramente, propõe-se uma taxonomia opera cional de personas de programação, organizada nos níveis *P−*1 a *P*3, que isola a variável identitária em prompts de código a partir das técnicas de *role prompting* consolidadas na literatura \[8\], \[12\]. Em seguida, produz-se evidência em pírica pareada sobre a relação entre o nível de especializa ção da persona, a complexidade da tarefa de refatoração e a qualidade do código resultante. Por fim, derivam-se dire trizes práticas para a engenharia de prompts na manutenção de software, sustentadas por dados experimentais e não por suposições anedóticas. 

O restante do artigo organiza-se como segue. A Seção II apresenta a fundamentação teórica e os trabalhos rela cionados; a Seção III define os objetivos e as questões de pesquisa; a Seção IV formula as hipóteses; a Seção V de screve o método e o desenho experimental; a Seção VI dis cute as ameaças à validade; a Seção VII apresenta o plano de trabalho; e a Seção VIII conclui. 

II. FUNDAMENTAÇÃO TEÓRICA E   
TRABALHOS RELACIONADOS 

A avaliação experimental do *persona prompting* em tarefas de refatoração apoia-se em dois corpos de conhecimento. Da engenharia de software, a refatoração define o objeto de estudo e as métricas de análise estática definem os in strumentos de medição \[1, 15\]. Da engenharia de prompts, as técnicas de condicionamento de LLMs definem o trata mento manipulado no experimento \[8, 12\]. A revisão dos estudos que aproximam essas duas áreas mostra o que ainda não foi investigado e indica onde este trabalho se insere. 

*A. Refatoração e Qualidade de Software* 

Refatorar é alterar a estrutura interna de um software para torná-lo mais legível e mais fácil de manter, sem mexer no comportamento externo observável \[1\]. Para avaliar essa melhoria de forma objetiva, recorre-se a métricas de quali dade estática. A complexidade ciclomática de McCabe, por exemplo, conta os caminhos linearmente independentes no fluxo de controle de um trecho de código \[15\] e funciona como indicador consolidado de testabilidade e de esforço de manutenção. Ferramentas de análise estática como o SonarQube põem esse e outros indicadores em prática, apli cando regras que apontam *code smells*, os padrões estrutu rais ligados à perda de qualidade \[6\]. Deste conjunto, o trabalho adota a complexidade ciclomática de McCabe e a contagem de *code smells* como medidas da qualidade es trutural do código refatorado. 

*B. Engenharia de Prompts e Persona Prompting* 

A engenharia de prompts reúne as técnicas que condi cionam a saída dos LLMs. Essa prática apoia-se na capaci dade dos modelos de executar tarefas a partir de instruções e de poucos exemplos fornecidos no próprio prompt \[2\], e a forma da instrução altera de modo mensurável o de sempenho, como demonstra o encadeamento de raciocínio (*chain-of-thought*) \[3\]. Catálogos de padrões registram soluções reutilizáveis, e entre elas está o padrão de per sona, que dá ao modelo um papel ou identidade para in fluenciar o estilo e o conteúdo da resposta \[8\]. Levanta mentos sistemáticos da área tratam essa atribuição de pa péis (*role prompting*) como uma técnica própria dentro da taxonomia de prompting \[12\], e a literatura recomenda lê la como interpretação de papéis, não como posse de traços humanos pelo modelo, justamente para evitar conclusões antropomórficas \[9\]. A escala de cinco níveis de per sona (*P−*1 a *P*3) usada neste estudo é essa técnica de *role prompting* \[8\], \[12\] operacionalizada, e gradua o nível de especialização e de congruência identitária do prompt para permitir a manipulação experimental controlada. 

*C. Estudos Empíricos sobre Persona Prompting em LLMs* 

A eficácia das personas em tarefas técnicas vem sendo in vestigada empiricamente há pouco tempo. Em tarefas obje tivas de pergunta-resposta, colocar uma persona no prompt de sistema não melhora o desempenho do LLM frente à ausência de persona, e o efeito aproxima-se do aleatório \[10\]. Mais do que isso, atributos identitários sem relação com a tarefa reduzem a acurácia de modelos de grande porte \[11\]. Resultados assim alimentam a suspeita de que boa parte do ganho creditado às personas seja, na verdade, um efeito placebo da engenharia de prompts. Essas inves tigações, porém, concentraram-se em pergunta-resposta e raciocínio; nenhuma abordou a manutenção de código. 

*D. LLMs Aplicados à Refatoração de Código* 

Na refatoração propriamente dita, há estudos empíricos que medem o que os LLMs conseguem fazer sobre código real. Ao refatorar código Java, esses modelos preservam o comportamento original só em parte e devolvem resul tados diferentes para o mesmo prompt repetido, o que ex põe seu caráter não-determinístico \[13\]. A qualidade do que produzem depende muito do contexto dado no prompt, pois quanto mais detalhada a especificação, maior a taxa de refatorações bem-sucedidas \[14\]. Esses trabalhos con firmam que o conteúdo do prompt pesa no resultado, mas nenhum isola o efeito específico da identidade (a persona) atribuída ao modelo. 

*E. Lacuna de Pesquisa* 

Em resumo, a literatura mostra um efeito nulo ou incon sistente da persona em pergunta-resposta e raciocínio \[10\], \[11\] e confirma que o resultado da refatoração é sensível ao conteúdo do prompt \[13\], \[14\]. Falta, no entanto, um estudo que isole e quantifique o efeito do nível de especialização da persona sobre a qualidade estrutural da refatoração. É essa lacuna que motiva o experimento controlado proposto neste trabalho. 

2  
III. OBJETIVOS E QUESTÕES DE PESQUISA 

Esta seção formaliza o objetivo geral, os objetivos especí ficos e as questões de pesquisa que decorrem do problema apresentado na Seção I. 

*A. Objetivo Geral* 

Avaliar empiricamente o impacto de diferentes níveis de especialização em *persona prompting* sobre a qualidade es trutural do código produzido por LLMs em tarefas de refa toração. 

*B. Objetivos Específicos* 

1\. Estabelecer os cinco níveis de prompts de persona (*P−*1: controle negativo; *P*0: neutro; *P*1: genérico; *P*2: especializado; *P*3: contextual); 

2\. Definir um benchmark controlado de tarefas de refa toração extraídas de projetos Java reais; 

3\. Executar o experimento fatorial que cruza os níveis de persona com tarefas de complexidades variadas; 

4\. Verificar a preservação do comportamento das refa torações geradas por meio das suítes de testes dos pro jetos hospedeiros, excluindo da análise as refatorações que alteram o comportamento observável; 

5\. Medir a qualidade estrutural das refatorações válidas pela complexidade ciclomática de McCabe \[15\] e pela contagem de *code smells* via SonarQube \[6\]. 

*C. Questões de Pesquisa* 

• RQ1: O nível de especialização da persona produz melhorias estatisticamente significativas na qualidade do código refatorado (redução de *code smells* e da complexidade ciclomática de McCabe)? 

• RQ2: Existe uma interação significativa entre a com plexidade da tarefa de refatoração e o nível de espe cialização da persona? 

IV. HIPÓTESES 

Para cada questão de pesquisa, testam-se as hipóteses nula (*H*0) e alternativa (*H*1) com nível de significância *α* \= 0*,*05\. 

• *H*10: A atribuição de personas não afeta as métricas de qualidade estrutural pós-refatoração. 

• *H*11: Personas especializadas e contextuais (*P*2, *P*3) geram código com menor complexidade ciclomática de McCabe e menor incidência de *code smells* em comparação com prompts neutros ou fora de domínio (*P*0, *P−*1). 

• *H*20: Não há efeito de interação entre o nível de espe cialização da persona e o tipo de refatoração aplicada. 

• *H*21: Há efeito de interação significativo, em que tarefas de refatoração de maior complexidade es trutural (Substituir Condicional por Polimorfismo) beneficiam-se mais do uso de personas especializadas, com maior redução de *code smells* e de complexi dade ciclomática de McCabe, do que tarefas estrutu ralmente simples. 

A não rejeição de *H*10 (ausência de diferença de quali dade entre o controle negativo *P−*1, o prompt neutro *P*0 e as personas especializadas *P*2 e *P*3) caracteriza o cenário de efeito placebo investigado pelo estudo, e sua confirmação exige, conforme o protocolo da Seção V, taxas de validade semelhantes entre as personas e equivalência nas métricas de qualidade. 

V. MÉTODO E DESENHO EXPERIMENTAL 

O estudo segue as fases do processo de experimentação em engenharia de software de Wohlin \[4\]: definição, planeja mento, operação, análise e interpretação, e apresentação. Esta seção detalha a definição e o planejamento do proto colo. 

*A. Tipo de Estudo* 

O trabalho adota o paradigma experimental da Engenharia de Software Empírica \[4\], isto é, um experimento contro lado e quantitativo que manipula variáveis independentes e testa hipóteses sobre o seu efeito, em contraste com abor dagens analíticas ou puramente observacionais. O desenho é fatorial misto. O fator persona é manipulado de forma intra-sujeito, pois cada trecho de código passa por todos os cinco níveis de persona, o que permite a análise pareada e atenua a variância entre trechos. O fator tipo de refatoração é manipulado entre sujeitos, pois cada trecho é associado a um único tipo, conforme a oportunidade de refatoração que apresenta. Essa escolha preserva a validade de construto, já que cada trecho recebe a refatoração que de fato cabe ao seu código. Como cada chamada ao modelo é independente e sem memória entre execuções, não há efeito de ordem entre tratamentos a controlar, o que dispensa a aleatorização da sequência. 

*B. Desenho Fatorial 5×3* 

A estrutura fatorial segue o método de experimentação de Wohlin \[4\], que recomenda o cruzamento de fatores para estimar efeitos principais e de interação. O desenho cruza dois fatores, o nível da persona (5 níveis) e o tipo de refa toração (3 níveis), o que forma 15 condições de tratamento (Tabela I). 

O fator persona origina-se da técnica de *role prompting*, também chamada persona prompting. Essa técnica é docu mentada como um padrão de prompt reutilizável no catál ogo de padrões de White et al. \[8\] e classificada como uma técnica própria na taxonomia sistemática de prompting de Schulhoff et al. \[12\]. Essas fontes, porém, documentam e classificam a técnica sem propor uma gradação de níveis de especialização. A contribuição metodológica deste es tudo é operacionalizar a técnica em uma escala ordinal de cinco níveis, construída para isolar a variável identitária 

3

ao longo de um gradiente controlado de especialização e de congruência com a tarefa: P-1, identidade incongruente (controle negativo); P0, ausência de persona (condição de referência); P1, identidade genérica; P2, identidade espe cializada; e P3, identidade especializada e contextualizada ao projeto. A presença de P0 (referência) ao lado de P-1 (controle negativo) permite separar o efeito da especializa ção do efeito da mera presença de uma persona. 

O fator tipo de refatoração deriva do catálogo de refa torações de Fowler \[1\], do qual se selecionam três refa torações que formam um gradiente de complexidade estru tural: Extrair Método (estrutural local), Substituir Número Mágico (semântica local) e Substituir Condicional por Polimorfismo (arquitetural). Esse gradiente é o que torna a hipótese de interação verificável, ou seja, permite testar se a especialização da persona pesa mais nas tarefas estru turalmente mais complexas. 

Como o tipo de refatoração é um fator entre-sujeitos, cada trecho é exposto às cinco condições de persona da col una correspondente ao seu tipo. 

Table 1: Condições de tratamento do desenho fatorial 5*×*3\. 

| Nível da persona  | Extrair Método | Subst. N. Mágico  | Subst. Cond.Polimorfismo |
| :---- | :---: | :---: | :---: |
| P-1 (controle negativo)  | C1  | C2  | C3 |
| P0 (neutro)  | C4  | C5  | C6 |
| P1 (genérico)  | C7  | C8  | C9 |
| P2 (especializado)  | C10  | C11  | C12 |
| P3 (contextual)  | C13  | C14  | C15 |

   
 

*C. Variáveis* 

Variáveis independentes: 

• Nível da persona (5 níveis), com os prompts fixados em inglês: P-1, controle negativo adversário (“You are an experienced artisan baker”); P0, neutro (sem persona); P1, genérico (“You are a developer”); P2, especializado (“You are a Senior Java Software Archi tect and Clean Code expert”); P3, contextual (persona especializada somada a diretrizes específicas do pro jeto). Em todas as condições, a instrução-base que so licita a refatoração é idêntica, e apenas o preâmbulo de persona varia, o que isola a variável identitária. 

• Tipo de refatoração (3 níveis), em complexidade cres cente: Extrair Método (alteração estrutural local), Substituir Número Mágico (alteração semântica local) e Substituir Condicional por Polimorfismo (alteração arquitetural baseada em Orientação a Objetos). 

Variável dependente: a qualidade estrutural do código refatorado, medida por dois indicadores: o delta da complexidade ciclomática de McCabe \[15\] (valor pós refatoração menos o valor da linha de base) e o delta da contagem de *code smells* apontados pelo SonarQube \[6\]; valores negativos indicam melhoria estrutural. 

Variáveis de controle: o modelo de LLM, fixado em uma versão específica de um modelo Gemini de tier Flash; a temperatura de decodificação, fixada em 0,2; o idioma do   
prompt (inglês); a instrução-base de refatoração; e o con junto de regras de análise estática do SonarQube. Critério de validade (não é variável dependente): a corretude comportamental, verificada pela aprovação inte gral na suíte de testes unitários do projeto hospedeiro no Defects4J \[5\]. As refatorações que não preservam o com portamento são excluídas da análise de qualidade. 

*D. Unidades Experimentais, Amostragem e Pipeline* 

A amostragem é estratificada e controlada: selecionam-se 10 trechos de código para cada um dos 3 tipos de refa toração, totalizando 30 trechos extraídos das versões cor rigidas (funcionais) dos projetos do Defects4J \[5\]. Cada trecho corresponde a um método ou classe que apresenta a oportunidade de refatoração. Os candidatos são identifica dos por indicadores do SonarQube (métodos longos ou de alta complexidade cognitiva para Extrair Método; números mágicos para Substituir Número Mágico; cadeias condi cionais extensas para Substituir Condicional por Polimor fismo) e confirmados manualmente quanto à pertinência. O Defects4J \[5\] fornece suítes de teste de alta cobertura, necessárias para a verificação de validade. 

Para conter o não-determinismo do modelo, aplicam-se 5 réplicas a cada condição. O desenho gera 750 observações (30 trechos *×* 5 níveis de persona *×* 5 réplicas). 

A operação segue um pipeline automatizado, alinhado às fases de Wohlin \[4\]: 

1\. extração das unidades experimentais e coleta da linha de base (execução dos testes e análise estática do código original); 

2\. envio do trecho e do prompt correspondente ao mod elo; 

3\. captura do código gerado e reinserção no projeto hos pedeiro; 

4\. execução da suíte de testes do Defects4J, que atua como portão de validade, e na qual as refatorações que quebram o comportamento são descartadas antes da etapa seguinte; 

5\. análise estática das refatorações válidas no Sonar Qube, com coleta da complexidade ciclomática e da contagem de *code smells*. 

*1\) Instrumentação* 

A execução é conduzida por um script em Python que au tomatiza todo o pipeline. O script monta cada prompt pela concatenação do preâmbulo de persona com a instrução base e o trecho de código, e envia a requisição à API do modelo com os parâmetros de controle fixados. A instrução-base exige que a resposta contenha apenas o código completo refatorado, em um único bloco e sem ex plicações, o que padroniza a extração automática do código gerado. Cada réplica recebe um status de execução: falha de extração (resposta fora do formato), falha de compi lação, falha na suíte de testes ou sucesso; os três primeiros classificam a réplica como inválida, com o motivo reg istrado. A verificação de validade executa a suíte de testes 

4

completa do projeto hospedeiro, o que captura inclusive re gressões fora da classe alterada. Para cada réplica válida, a análise estática mede as métricas no escopo do arquivo alterado, e o script registra trecho, tipo de refatoração, persona, réplica, status, deltas de qualidade, contagem de tokens e latência em um conjunto de dados estruturado. O registro de tokens permite quantificar o custo adicional dos preâmbulos identitários, o que conecta os resultados à relevância prática do estudo. 

*E. Análise Estatística* 

Uma célula do desenho (a combinação de um trecho com uma persona) é considerada válida quando ao menos uma de suas 5 réplicas preserva o comportamento, e seu valor em cada métrica é a mediana das réplicas válidas, o que es tabiliza a medida diante do não-determinismo do modelo; o número de réplicas válidas por célula é reportado junto aos resultados. Após a agregação, cada métrica de quali dade produz até 150 valores (30 trechos *×* 5 personas), so bre os quais incidem os testes. A taxa de validade de cada persona, isto é, a proporção de refatorações que preservam o comportamento, é reportada como estatística descritiva secundária, pois evidencia o atrito operacional gerado por cada prompt antes da análise de qualidade. A análise de qualidade é conduzida em regime *complete-case*, sobre os trechos cujas refatorações são válidas em todas as personas, o que preserva o pareamento intra-sujeito. 

A análise primária aplica o Teste de Friedman ao efeito principal da persona, separadamente para cada métrica de qualidade, com análise *post-hoc* de Nemenyi para as com parações par a par. A escolha de testes não-paramétricos justifica-se pela natureza pareada dos dados e pela não normalidade esperada das saídas do modelo. A análise secundária e exploratória avalia a interação entre persona e tipo de refatoração por ANOVA sobre postos alinhados (*Aligned Rank Transform*) \[16\], método não-paramétrico que admite desenhos fatoriais; seus resultados são inter pretados com ressalva, dado o poder estatístico limitado da amostra estratificada. Adota-se nível de significância de *α* \= 0*,*05\. 

Como a não rejeição de uma hipótese nula não consti tui prova de ausência de efeito, a interpretação do efeito placebo apoia-se também em um teste de equivalência, o procedimento *Two One-Sided Tests* (TOST) \[17\]. Por ele, declara-se equivalência prática entre dois níveis de persona quando a diferença observada nas métricas de qualidade permanece dentro de uma margem de equivalência previa mente definida. Dessa forma, o efeito placebo é sustentado por evidência positiva de equivalência entre o controle neg ativo, o nível neutro e as personas especializadas, e não ape nas pela ausência de diferença estatisticamente significa tiva. O veredito de placebo exige a leitura conjunta das duas frentes, isto é, taxas de validade semelhantes entre as per sonas e equivalência nas métricas de qualidade. Como ver ificação de robustez ao descarte do regime *complete-case*, uma análise de sensibilidade reestima o efeito da persona por um modelo linear de efeitos mistos \[18\], com o trecho como efeito aleatório e com todas as réplicas válidas; a con vergência entre as duas análises indica que a conclusão não é um artefato do filtro de validade.   
VI. AMEAÇAS À VALIDADE 

Esta seção discute as ameaças à validade do protocolo nas quatro dimensões clássicas da experimentação em engen haria de software \[4\], junto às mitigações adotadas. 

*A. Validade de Construto* 

A qualidade estrutural é representada por dois indicadores objetivos, a complexidade ciclomática de McCabe \[15\] e a contagem de *code smells* \[6\]. Como a análise estática produz falsos positivos, uma amostra dos *smells* contabi lizados passa por validação manual, para distinguir prob lemas reais de ruído da ferramenta. Há ainda o risco de o modelo descartar a persona de controle negativo (*P−*1, o padeiro artesanal) por alinhamento de contexto, ao perceber que ela é incongruente com a tarefa. Caso isso ocorra, *P−*1 aproxima-se de *P*0 e perde a função de controle. Para de tectar esse risco, uma amostra das saídas geradas sob *P−*1 passa por inspeção qualitativa, que verifica se o modelo manteve o papel atribuído ou se o ignorou. 

*B. Validade Interna* 

O não-determinismo do modelo é mitigado pelas 5 réplicas por condição e pela agregação das medidas por mediana. O filtro de validade introduz um risco de viés de seleção, pois a persona de controle negativo tende a quebrar mais código e dela sobram apenas as refatorações que passaram nos testes, o que poderia inflar artificialmente sua quali dade média. Esse risco é tratado de três formas: a taxa de validade de cada persona é reportada descritivamente, a comparação de qualidade ocorre em regime *complete case*, restrito aos trechos com refatoração válida em todas as personas, e uma análise de sensibilidade com modelo de efeitos mistos verifica se as conclusões se mantêm quando todas as réplicas válidas são aproveitadas. 

*C. Validade Externa* 

O escopo limita-se à linguagem Java e a um único mod elo de LLM, o que restringe a generalização dos resultados a outras linguagens e modelos. O uso do Defects4J \[5\], por outro lado, garante código real e suítes de teste ro bustas, e a refatoração de sistemas orientados a objetos representa um paradigma maduro e amplamente praticado. Além disso, como o Defects4J é um benchmark público, o modelo pode ter tido contato com esse código durante o treinamento; esse efeito de contaminação atinge igual mente as cinco condições de persona, que operam sobre os mesmos trechos, e por isso não compromete a comparação pareada, embora limite a generalização para código inédito. 

*D. Validade de Conclusão* 

O tamanho da amostra (30 trechos, 750 observações) con fere poder adequado à análise primária do efeito principal da persona. Contudo, o uso do regime *complete-case* para as métricas de qualidade impõe um risco de viés de seleção condicional (viés de sobrevivência) caso o controle nega tivo (*P−*1) apresente alto atrito, reduzindo o dataset pareado a trechos de menor complexidade estrutural. Esse risco é mitigado diretamente pela elevação da taxa de validade a 

5

indicador primário via Teste Q de Cochran (capturando o efeito da persona no atrito) e pela execução do LMM como análise de sensibilidade, garantindo que as conclusões so bre o efeito placebo não sejam artefatos do descarte de da dos. A análise da interação entre persona e tipo de refa toração dispõe de menos dados por estrato, motivo pelo qual é tratada como exploratória, e suas conclusões são ap resentadas com a devida cautela. 

VII. PLANO DE TRABALHO E CRONOGRAMA 

O plano de trabalho distribui-se em oito semanas, conforme a Tabela II. 

Table 2: Cronograma de execução. 

| Semana  | Atividade |
| :---: | ----- |
| 1  | Revisão bibliográfica e definição rigorosa dosprompts (*P−*1 a *P*3). |
| 2  | Extração dos trechos do Defects4J e coleta dalinha de base. |
| 3  | Execução automatizada das condições *P−*1, *P*0 e *P*1. |
| 4  | Execução automatizada das condições *P*2 e *P*3. |
| 5  | Replicação das execuções e coleta das métricasno SonarQube. |
| 6  | Consolidação do conjunto de dados e aplicaçãodos testes estatísticos. |
| 7  | Discussão dos resultados e avaliação do efeitoplacebo. |
| 8  | Redação final e empacotamento dos artefatos(Ciência Aberta). |

VIII. CONCLUSÃO 

Esta proposta delineia um experimento controlado para in vestigar o impacto do *persona prompting* na qualidade de tarefas de refatoração de código. Alinhado aos princípios da Engenharia de Software Experimental, o estudo busca fornecer evidência estatística sobre se a especialização da persona melhora a qualidade do código refatorado ou se atua como efeito placebo, e pretende oferecer diretrizes em píricas para o uso de LLMs na manutenção de software, no lugar de heurísticas adotadas por suposição. 

AGRADECIMENTOS 

Os autores agradecem ao corpo docente do Programa de Engenharia de Software da UNIJUÍ pela orientação. 

REFERENCES 

\[1\] M. Fowler, *Refactoring: Improving the Design of Ex isting Code*, 2nd ed. Boston, MA, USA: Addison Wesley, 2018\. 

\[2\] T. Brown et al., “Language models are few-shot learn ers,” in *Advances in Neural Information Processing Systems (NeurIPS)*, vol. 33, 2020, pp. 1877–1901.   
\[3\] J. Wei et al., “Chain-of-thought prompting elicits rea soning in large language models,” in *Advances in Neural Information Processing Systems (NeurIPS)*, vol. 35, 2022, pp. 24824–24837. 

\[4\] C. Wohlin, P. Runeson, M. Höst, M. C. Ohls son, B. Regnell, and A. Wesslén, *Experimentation in Software Engineering*, 2nd ed. Berlin, Germany: Springer, 2012\. 

\[5\] R. Just, D. Jalali, and M. D. Ernst, “Defects4J: A database of existing faults to enable controlled testing studies for Java programs,” in *Proc. Int. Symp. Soft ware Testing and Analysis (ISSTA)*, 2014, pp. 437– 440\. 

\[6\] G. A. Campbell and P. P. Papapetrou, *SonarQube in Action*. Shelter Island, NY, USA: Manning Publica tions, 2013\. 

\[7\] Stack Overflow, “2024 Developer Survey,” 2024\. \[Online\]. Available: https://survey.stackoverflow.co/2024/ 

\[8\] J. White et al., “A prompt pattern catalog to enhance prompt engineering with ChatGPT,” *arXiv preprint arXiv:2302.11382*, 2023\. 

\[9\] M. Shanahan, K. McDonell, and L. Reynolds, “Role play with large language models,” *Nature*, vol. 623, pp. 493–498, 2023\. 

\[10\] M. Zheng, J. Pei, L. Logeswaran, M. Lee, and D. Jur gens, “When ’a helpful assistant’ is not really helpful: Personas in system prompts do not improve perfor mances of large language models,” in *Findings of the Association for Computational Linguistics: EMNLP 2024*, 2024\. 

\[11\] P. H. Luz de Araujo, P. Röttger, D. Hovy, and B. Roth, “Principled personas: Defining and measuring the in tended effects of persona prompting on task perfor mance,” *arXiv preprint arXiv:2508.19764*, 2025\. 

\[12\] S. Schulhoff et al., “The prompt report: A system atic survey of prompting techniques,” *arXiv preprint arXiv:2406.06608*, 2024\. 

\[13\] K. DePalma, I. Miminoshvili, C. Henselder, K. Moss, and E. A. AlOmar, “Exploring ChatGPT’s code refac toring capabilities: An empirical study,” *Expert Sys tems with Applications*, vol. 249, art. 123602, 2024\. 

\[14\] B. Liu et al., “An empirical study on the potential of LLMs in automated software refactoring,” *arXiv preprint arXiv:2411.04444*, 2024\. 

\[15\] T. J. McCabe, “A complexity measure,” *IEEE Trans actions on Software Engineering*, vol. SE-2, no. 4, pp. 308–320, 1976\. 

\[16\] J. O. Wobbrock, L. Findlater, D. Gergle, and J. J. Hig gins, “The aligned rank transform for nonparametric factorial analyses using only ANOVA procedures,” in *Proc. SIGCHI Conf. Human Factors in Computing Systems (CHI)*, 2011, pp. 143–146. 

6  
\[17\] D. Lakens, “Equivalence tests: A practical primer for t tests, correlations, and meta-analyses,” *Social Psy chological and Personality Science*, vol. 8, no. 4, pp. 355–362, 2017\. 

\[18\] J. C. Pinheiro and D. M. Bates, *Mixed-Effects Mod els in S and S-PLUS*. New York, NY, USA: Springer, 2000\. 

7