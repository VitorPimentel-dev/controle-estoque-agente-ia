package br.com.realtec.controleestoque.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgenteService {

    private static final int MAX_LOG = 4000;

    private final BrowserService browser;
    private final LogExecucaoAgenteService logService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ollama.model:qwen2.5:1.5b}")
    private String model;

    public AgenteService(
            BrowserService browser,
            LogExecucaoAgenteService logService,
            @Value("${ollama.url:http://localhost:11434}") String ollamaUrl) {

        this.browser = browser;
        this.logService = logService;

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(ollamaUrl)
                .build();
    }

    public synchronized String executarComando(String promptUsuario) {

        List<String> acoesExecutadas = new ArrayList<>();

        String resultadoEsperado =
                "Executar o comando pela interface web, conferir o resultado exibido na tela e informar se corresponde ao esperado.";

        try {
            String norm = normalizarTexto(promptUsuario);
            boolean isPedido = norm.contains("pedido");
            boolean isCancelarPedido = isPedido && norm.contains("cancel");
            boolean isNovoPedido = isPedido && !isCancelarPedido && norm.matches(".*(cri|nov|abr|registr|faz|faca|tent).*");

            boolean isMovimentacaoEstoque = !isPedido
                    && norm.matches(".*(diminu|aument|entrada|saida|ajust|baix|repor|adicion\\w*\\s+\\d+\\s+(?:ao\\s+)?estoque|retir).*")
                    && !norm.matches(".*(cadast|nov|preco|valor|r\\$).*");

            boolean isAlteracaoProduto = !isPedido && !isMovimentacaoEstoque
                    && norm.matches(".*(alter|atualiz|edit|mud|troc).*");

            // "qual" incluido: "qual o preco do mouse" caia em cadastro
            boolean isConsultaApenas = !isPedido && !isMovimentacaoEstoque && !isAlteracaoProduto
                    && norm.matches("^(consult|list|mostr|ver|qual|quais|quanto|busc|pesquis).*")
                    && !norm.matches(".*(cadast|cri|adicion|nov|registr|inclu|insir).*");

            boolean isCadastroProduto = !isPedido && !isMovimentacaoEstoque && !isAlteracaoProduto && !isConsultaApenas
                    && (norm.matches(".*(cadast|cri|adicion|nov|registr|inclu|insir).*")
                    || norm.matches(".*(valor|preco|r\\$|estoque).*"));

            String codigoProdutoExplicito = extrairCodigoProdutoExplicito(promptUsuario);
            String codigoPedidoExplicito = isPedido ? extrairCodigoPedidoExplicito(norm) : null;

            String rotaInicial = isPedido ? "/pedidos" : "/produtos";
            String telaInicial = limitarTela(browser.abrir(rotaInicial));
            acoesExecutadas.add("abrirPagina({\"caminho\":\"" + rotaInicial + "\"})");

            ArrayNode messages = objectMapper.createArrayNode();

            messages.add(objectMapper.createObjectNode()
                    .put("role", "system")
                    .put("content", systemPrompt(isPedido, isAlteracaoProduto, isCadastroProduto, isMovimentacaoEstoque)));

            String contextoUsuario = isPedido
                    ? "TAREFA:\n\"" + promptUsuario + "\""
                    : "TAREFA:\n\"" + promptUsuario + "\"\n\nTELA ATUAL (" + rotaInicial + "):\n" + telaInicial;

            messages.add(objectMapper.createObjectNode()
                    .put("role", "user")
                    .put("content", contextoUsuario));

            String respostaFinal = "";
            String ultimaTela = telaInicial;
            boolean houveErroFerramenta = false;
            boolean comandoDeEscrita = isPedido || isCadastroProduto || isAlteracaoProduto || isMovimentacaoEstoque
                    || norm.matches(".*(remov|exclu|confirm|cancel|realiz).*");
            int maxIteracoes = 12;

            for (int i = 0; i < maxIteracoes; i++) {

                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("stream", false);
                body.set("messages", messages);
                body.set("tools", criarDefinicaoTools(isPedido, isAlteracaoProduto, isCadastroProduto, isMovimentacaoEstoque));

                ObjectNode options = objectMapper.createObjectNode();
                options.put("num_ctx", 2048);
                options.put("num_predict", 220);
                options.put("temperature", 0);
                body.set("options", options);

                System.out.println(">> chamando modelo (iteracao " + (i + 1) + ")");

                String responseJson = chamarModelo(body);
                JsonNode message = objectMapper.readTree(responseJson).path("message");

                if (message.isMissingNode() || message.isNull()) {
                    throw new IllegalStateException("O modelo não retornou mensagem válida.");
                }

                messages.add(message);

                JsonNode toolCalls = message.path("tool_calls");

                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String texto = message.path("content").asText("");
                    boolean clicouBotao = clicouEmBotaoFinal(acoesExecutadas, isPedido, isCadastroProduto, isAlteracaoProduto, isMovimentacaoEstoque);
                    if (comandoDeEscrita && !clicouBotao && !houveErroFerramenta && i < maxIteracoes - 1) {
                        String instrucao;
                        if (isPedido) {
                            instrucao = "Não escreva texto. Chame agora a tool 'processarPedido' com identificador, lista de itens e acaoFinal.";
                        } else if (isMovimentacaoEstoque) {
                            instrucao = "Não escreva texto. Chame agora a tool 'movimentarEstoque' passando produto, operacao ('entrada' ou 'saida') e quantidade da TAREFA.";
                        } else if (isAlteracaoProduto) {
                            instrucao = "Não escreva texto. Chame agora a tool 'atualizarProduto' passando codigo, descricao e precoUnitario da TAREFA.";
                        } else if (isCadastroProduto) {
                            instrucao = "Não escreva texto. Chame agora a tool 'cadastrarProduto' passando codigo, descricao, precoUnitario e quantidadeEstoque da TAREFA.";
                        } else {
                            instrucao = "Não escreva texto. Chame agora a tool correspondente à TAREFA.";
                        }
                        messages.add(objectMapper.createObjectNode()
                                .put("role", "user")
                                .put("content", instrucao));
                        continue;
                    }
                    respostaFinal = texto;
                    break;
                }

                for (JsonNode call : toolCalls) {

                    JsonNode fn = call.path("function");
                    String nomeFuncao = fn.path("name").asText();

                    JsonNode args = fn.path("arguments");
                    if (args.isTextual()) {
                        args = objectMapper.readTree(args.asText());
                    }

                    // Garante identificador correto (informado no prompt ou AUTO para gerar no banco)
                    if ("processarPedido".equals(nomeFuncao) && args instanceof ObjectNode objArgs) {
                        objArgs.put("identificador", codigoPedidoExplicito != null ? codigoPedidoExplicito : "AUTO");

                        // Acao final decidida pela frase, nao pelo modelo
                        String acao = isCancelarPedido ? "cancelar"
                                : norm.contains("confirm") ? "confirmar"
                                  : "rascunho";
                        objArgs.put("acaoFinal", acao);

                        // Deduplica itens repetidos gerados pelo modelo
                        if (objArgs.path("itens").isArray()) {
                            Map<String, ObjectNode> unicos = new LinkedHashMap<>();
                            for (JsonNode it : objArgs.path("itens")) {
                                if (it instanceof ObjectNode itemObj) {
                                    String prodKey = normalizarTexto(itemObj.path("produto").asText(""));
                                    if (!prodKey.isEmpty()) {
                                        unicos.put(prodKey, itemObj);
                                    }
                                }
                            }
                            ArrayNode itensLimpos = objectMapper.createArrayNode();
                            unicos.values().forEach(itensLimpos::add);
                            objArgs.set("itens", itensLimpos);
                        }

                        var mAltQtd = Pattern.compile("(?:alter|mud|troc|ajust)\\w*\\s+(?:a\\s+)?quantidade\\s+para\\s+(\\d+)")
                                .matcher(norm);
                        if (mAltQtd.find() && objArgs.path("itens").isArray() && objArgs.path("itens").size() == 1) {
                            JsonNode primeiroItem = objArgs.path("itens").get(0);
                            if (primeiroItem instanceof ObjectNode itemObj) {
                                itemObj.put("quantidade", mAltQtd.group(1));
                            }
                        }
                    }

                    // Usa o codigo informado na frase; so usa AUTO se o usuario NAO informou codigo
                    if ("cadastrarProduto".equals(nomeFuncao) && args instanceof ObjectNode objArgs) {
                        if (codigoProdutoExplicito != null) {
                            objArgs.put("codigo", codigoProdutoExplicito);
                        } else {
                            String codModelo = objArgs.path("codigo").asText("").trim();
                            if (codModelo.isEmpty() || "AUTO".equalsIgnoreCase(codModelo)) {
                                objArgs.put("codigo", "AUTO");
                            } else {
                                String digitos = codModelo.replaceAll("[^\\d]", "");
                                if (digitos.isEmpty() || !promptUsuario.contains(digitos)) {
                                    objArgs.put("codigo", "AUTO");
                                }
                            }
                        }
                    }

                    // Evita codigo inventado na movimentacao de estoque quando o usuario citou o nome do produto
                    if ("movimentarEstoque".equals(nomeFuncao) && args instanceof ObjectNode objArgs) {
                        if (codigoProdutoExplicito != null) {
                            objArgs.put("produto", codigoProdutoExplicito);
                        } else {
                            // nome vem da frase, nao do modelo (ele chegou a devolver "Realtec")
                            String nomeExtraido = extrairNomeProdutoMovimentacao(norm);
                            if (!nomeExtraido.isEmpty()) {
                                objArgs.put("produto", nomeExtraido);
                            }
                        }

                        // operacao tambem decidida pela frase
                        if (norm.matches(".*(diminu|retir|baix|said|remov|subtr).*")) {
                            objArgs.put("operacao", "saida");
                        } else if (norm.matches(".*(aument|adicion|repor|entrad|acrescent).*")) {
                            objArgs.put("operacao", "entrada");
                        }
                    }

                    acoesExecutadas.add(nomeFuncao + "(" + args + ")");

                    Map<String, Object> resultadoTool =
                            executarFerramenta(nomeFuncao, args, isNovoPedido, norm.contains("estoque"));

                    if (resultadoTool.containsKey("tela")) {
                        ultimaTela = String.valueOf(resultadoTool.get("tela"));
                    }

                    if (Boolean.FALSE.equals(resultadoTool.get("sucesso"))) {
                        houveErroFerramenta = true;
                        String erroTool = String.valueOf(resultadoTool.get("erro"));
                        acoesExecutadas.add("  falhou: " + erroTool);
                        respostaFinal = "Falha ao executar na interface: " + erroTool;
                    }

                    ObjectNode toolMsg = objectMapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_name", nomeFuncao);
                    toolMsg.put("content", objectMapper.valueToTree(resultadoTool).toString());
                    messages.add(toolMsg);
                }

                if (houveErroFerramenta) {
                    break;
                }
            }

            boolean clicouBotao = clicouEmBotaoFinal(acoesExecutadas, isPedido, isCadastroProduto, isAlteracaoProduto, isMovimentacaoEstoque);

            // Modelo pequeno as vezes devolve texto vazio apos executar: usa a tela como resposta
            if (respostaFinal.isBlank() && !houveErroFerramenta && clicouBotao && !ultimaTela.isBlank()) {
                respostaFinal = ultimaTela;
            }

            if (respostaFinal.isBlank()) {
                respostaFinal = "O agente não retornou uma resposta final.";
                houveErroFerramenta = true;
            }

            if (comandoDeEscrita && !clicouBotao) {
                houveErroFerramenta = true;
            }

            if (!houveErroFerramenta && (isCadastroProduto || isAlteracaoProduto || isMovimentacaoEstoque)) {
                String telaLower = ultimaTela.toLowerCase();
                boolean confirmou = telaLower.contains("sucesso")
                        || telaLower.contains("atualizad")
                        || telaLower.contains("registrad")
                        || telaLower.contains("movimentad");
                if (!confirmou) {
                    houveErroFerramenta = true;
                    respostaFinal = "Não houve confirmação de sucesso na tela /produtos.";
                }
            }

            String logAcoes = acoesExecutadas.isEmpty()
                    ? "Nenhuma ação executada (resposta direta)"
                    : String.join("\n", acoesExecutadas);

            String conclusao = houveErroFerramenta
                    ? "Execução concluída com falhas em alguma ação da interface. Conferir o resultado observado."
                    : "Comando processado pelo Agente IA pela interface.";

            registrarLog(
                    cortar(promptUsuario),
                    cortar(logAcoes),
                    resultadoEsperado,
                    cortar(respostaFinal),
                    conclusao,
                    !houveErroFerramenta
            );

            return respostaFinal;

        } catch (Exception e) {

            String erroMsg = cortar(
                    "Erro ao processar comando no Agente: " + e.getMessage(),
                    500
            );

            registrarLog(
                    cortar(promptUsuario),
                    acoesExecutadas.isEmpty()
                            ? "Falha antes da execução de ações"
                            : cortar(String.join("\n", acoesExecutadas)),
                    resultadoEsperado,
                    erroMsg,
                    "Falha na execução do Agente IA.",
                    false
            );

            return erroMsg;
        }
    }

    // falha ao gravar log (ex: coluna menor que 4000) nao pode derrubar a resposta
    private void registrarLog(String comando, String acoes, String esperado, String observado, String conclusao, boolean sucesso) {
        try {
            logService.registrarExecucao(comando, acoes, esperado, observado, conclusao, sucesso);
        } catch (Exception e) {
            System.out.println(">> falha ao gravar log: " + e.getMessage());
        }
    }

    private String extrairCodigoPedidoExplicito(String norm) {
        if (norm == null) return null;
        Matcher m = Pattern.compile("(?:ped-|pedido\\s*(?:de\\s+codigo\\s+|cod(?:igo)?\\s+|n[o°º]?\\s*)?|cod(?:igo)?\\s*(?:n[o°º]?\\s*)?)(?:ped-)?(\\d{1,5})\\b")
                .matcher(norm);
        if (m.find()) {
            String num = m.group(1);
            return "PED-" + (num.length() < 3 ? String.format("%03d", Integer.parseInt(num)) : num);
        }
        return null;
    }

    private String extrairNomeProdutoMovimentacao(String norm) {
        // tira pontuacao final para o $ das regex casar
        String n = norm.replaceAll("[.!?,;]+$", "").trim();
        Matcher m1 = Pattern.compile("(?:quantidade\\s+de|estoque\\s+(?:de|do|da)|unidades\\s+de)\\s+([a-z0-9\\s-]+)$")
                .matcher(n);
        if (m1.find()) {
            return m1.group(1).trim();
        }
        Matcher m2 = Pattern.compile("(?:diminu\\w*|aument\\w*|retir\\w*|adicion\\w*|baix\\w*|repor)\\s+(?:em\\s+)?\\d+\\s+([a-z0-9\\s-]+)$")
                .matcher(n);
        if (m2.find()) {
            return m2.group(1).replaceAll("^(?:de|do|da|os|as)\\s+", "").trim();
        }
        return "";
    }

    private String extrairCodigoProdutoExplicito(String prompt) {
        if (prompt == null) return null;
        Matcher m1 = Pattern.compile("(?:prd-|produto\\s*(?:de\\s+c[oó]digo\\s+|c[oó]d(?:igo)?\\s+|n[º°o]?\\s*)?|c[oó]d(?:igo)?\\s*(?:n[º°o]?\\s*)?)(\\d{1,5})\\b", Pattern.CASE_INSENSITIVE)
                .matcher(prompt);
        if (m1.find()) {
            String num = m1.group(1);
            return "PRD-" + (num.length() < 3 ? String.format("%03d", Integer.parseInt(num)) : num);
        }
        Matcher m2 = Pattern.compile("(?:cadast\\w*|registr\\w*|cri\\w*)\\s+(?:o\\s+)?(\\d{3,5})\\b", Pattern.CASE_INSENSITIVE)
                .matcher(prompt);
        if (m2.find()) {
            return "PRD-" + m2.group(1);
        }
        return null;
    }

    private String normalizarTexto(String s) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // colapsa quebras de linha: matches(".*...*") nao atravessa \n
        return semAcento.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private boolean clicouEmBotaoFinal(List<String> acoes, boolean isPedido, boolean isCadastroProduto, boolean isAlteracaoProduto, boolean isMovimentacaoEstoque) {
        if (isPedido) {
            return acoes.stream().anyMatch(a -> a.startsWith("processarPedido("));
        }
        if (isCadastroProduto) {
            return acoes.stream().anyMatch(a -> a.startsWith("cadastrarProduto("));
        }
        if (isAlteracaoProduto) {
            return acoes.stream().anyMatch(a -> a.startsWith("atualizarProduto("));
        }
        if (isMovimentacaoEstoque) {
            return acoes.stream().anyMatch(a -> a.startsWith("movimentarEstoque("));
        }
        return acoes.stream().anyMatch(a ->
                a.startsWith("clicar(")
                        && (a.contains("Salvar") || a.contains("Entrada") || a.contains("Saída") || a.contains("Buscar")));
    }

    private String limitarTela(String tela) {
        if (tela == null) return "";
        return tela.length() > 2400
                ? tela.substring(0, 2400) + "..."
                : tela;
    }

    private String cortar(String s) {
        return cortar(s, MAX_LOG);
    }

    private String cortar(String s, int max) {
        return s == null || s.length() <= max
                ? s
                : s.substring(0, max - 3) + "...";
    }

    private String chamarModelo(ObjectNode body) {
        return restClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private Map<String, Object> executarFerramenta(String nome, JsonNode args, boolean isNovoPedido, boolean consultarEstoque) {

        System.out.println(">> ferramenta: " + nome + " " + args);

        Map<String, Object> result = new LinkedHashMap<>();

        try {

            switch (nome) {

                case "abrirPagina" -> result.put(
                        "tela", limitarTela(browser.abrir(args.path("caminho").asText())));

                case "clicar" -> result.put(
                        "tela", limitarTela(browser.clicar(args.path("seletor").asText())));

                case "preencher" -> result.put(
                        "resultado",
                        browser.preencher(
                                args.path("seletor").asText(),
                                args.path("valor").asText()));

                case "selecionar" -> result.put(
                        "resultado",
                        browser.selecionar(
                                args.path("seletor").asText(),
                                args.path("valor").asText()));

                case "cadastrarProduto" -> result.put(
                        "tela",
                        limitarTela(browser.cadastrarProdutoNaTela(
                                args.path("codigo").asText("AUTO"),
                                args.path("descricao").asText(""),
                                args.path("precoUnitario").asText(""),
                                args.path("quantidadeEstoque").asText("0"))));

                case "movimentarEstoque" -> result.put(
                        "tela",
                        limitarTela(browser.movimentarEstoqueNaTela(
                                args.path("produto").asText(""),
                                args.path("operacao").asText("saida"),
                                args.path("quantidade").asText("0"))));

                case "atualizarProduto" -> result.put(
                        "tela",
                        limitarTela(browser.atualizarProdutoNaTela(
                                args.path("codigo").asText(""),
                                args.path("descricao").asText(""),
                                args.path("precoUnitario").asText(""))));

                case "processarPedido" -> {
                    List<Map<String, Object>> itens = new ArrayList<>();
                    JsonNode itensNode = args.path("itens");
                    if (itensNode.isArray()) {
                        itens = objectMapper.convertValue(itensNode, new TypeReference<>() {});
                    }
                    String telaPedido = browser.processarPedidoNaTela(
                            args.path("identificador").asText("AUTO"),
                            itens,
                            args.path("acaoFinal").asText("confirmar"),
                            isNovoPedido);
                    if (consultarEstoque) {
                        String telaProdutos = browser.abrir("/produtos");
                        result.put("tela", limitarTela(telaPedido + "\n\nESTOQUE ATUAL (/produtos):\n" + telaProdutos));
                    } else {
                        result.put("tela", limitarTela(telaPedido));
                    }
                }

                case "lerTela" -> result.put("tela", limitarTela(browser.lerTela()));

                default -> {
                    result.put("sucesso", false);
                    result.put("erro", "Ferramenta desconhecida: " + nome);
                    return result;
                }
            }

            result.put("sucesso", true);

        } catch (Exception e) {

            System.out.println(">> erro na ferramenta: " + e.getMessage());
            result.put("sucesso", false);
            result.put("erro", cortar(e.getMessage(), 500));
        }

        return result;
    }

    private String systemPrompt(boolean isPedido, boolean isAlteracaoProduto, boolean isCadastroProduto, boolean isMovimentacaoEstoque) {
        if (isPedido) {
            return "Você opera a tela /pedidos do sistema Realtec.\n"
                    + "ORDEM OBRIGATÓRIA:\n"
                    + "Passo 1: Chame 'processarPedido' extraindo SOMENTE da TAREFA:\n"
                    + "  - identificador: código no padrão PED-XXX se a frase citar um número; se a frase NÃO citar número de pedido, passe \"AUTO\"\n"
                    + "  - itens: lista dos itens com quantidade final (ou lista vazia [] se for apenas cancelar/confirmar pedido existente)\n"
                    + "  - acaoFinal: \"confirmar\", \"cancelar\" ou \"rascunho\"\n"
                    + "Passo 2: Após executar, leia o TEXTO retornado e responda em português informando o status do pedido, o valor total e o estoque.";
        }
        if (isMovimentacaoEstoque) {
            return "Você movimenta o estoque de um produto existente na tela /produtos.\n"
                    + "ORDEM OBRIGATÓRIA:\n"
                    + "Passo 1: Chame 'movimentarEstoque' extraindo da TAREFA o nome ou código exato do produto citado, operacao ('entrada' ou 'saida') e quantidade.\n"
                    + "Passo 2: Responda em português de forma curta confirmando o novo saldo exibido na tela.";
        }
        if (isAlteracaoProduto) {
            return "Você altera um produto existente na tela /produtos do sistema Realtec.\n"
                    + "ORDEM OBRIGATÓRIA:\n"
                    + "Passo 1: Chame 'atualizarProduto' passando o codigo (padrão PRD-XXX), a nova descricao (ou vazio se não mudou) e o novo precoUnitario (ou vazio se não mudou).\n"
                    + "Passo 2: Responda em português de forma curta confirmando o que apareceu na tela.";
        }
        if (isCadastroProduto) {
            return "Você cadastra um novo produto na tela /produtos do sistema Realtec.\n"
                    + "ORDEM OBRIGATÓRIA:\n"
                    + "Passo 1: Chame 'cadastrarProduto' extraindo da TAREFA:\n"
                    + "  - codigo: código PRD-XXX se informado na frase, ou \"AUTO\" se não houver código\n"
                    + "  - descricao: nome do produto\n"
                    + "  - precoUnitario: preço numérico (ex: 1200.00)\n"
                    + "  - quantidadeEstoque: quantidade numérica (ex: 5)\n"
                    + "Passo 2: Responda em português de forma curta confirmando o produto cadastrado na tela.";
        }
        return "Você consulta a tela /produtos do sistema Realtec. Leia o TEXTO retornado e responda em português de forma clara e curta.";
    }

    // Pares nome, descricao em ordem fixa (Map.of varia a ordem entre execucoes)
    private static Map<String, String> props(String... pares) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            m.put(pares[i], pares[i + 1]);
        }
        return m;
    }

    private ArrayNode criarDefinicaoTools(boolean isPedido, boolean isAlteracaoProduto, boolean isCadastroProduto, boolean isMovimentacaoEstoque) {

        ArrayNode tools = objectMapper.createArrayNode();

        if (isPedido) {
            ObjectNode itemProps = objectMapper.createObjectNode();
            itemProps.set("produto", objectMapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "Nome simples do produto pedido na tarefa (ex: monitor, teclado, mouse)"));
            itemProps.set("quantidade", objectMapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "Quantidade numérica FINAL após qualquer alteração mencionada na frase"));

            ObjectNode itemSchema = objectMapper.createObjectNode();
            itemSchema.put("type", "object");
            itemSchema.set("properties", itemProps);

            ObjectNode itensArray = objectMapper.createObjectNode();
            itensArray.put("type", "array");
            itensArray.put("description", "Lista apenas dos itens solicitados na tarefa (vazio [] se for apenas cancelar/confirmar pedido existente)");
            itensArray.set("items", itemSchema);

            ObjectNode properties = objectMapper.createObjectNode();
            properties.set("identificador", objectMapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "Código PED-XXX se informado na frase, ou \"AUTO\" se não houver número"));
            properties.set("itens", itensArray);
            properties.set("acaoFinal", objectMapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "confirmar, cancelar ou rascunho"));

            ArrayNode req = objectMapper.createArrayNode();
            req.add("identificador");
            req.add("acaoFinal");

            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.set("properties", properties);
            parameters.set("required", req);

            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", "processarPedido");
            function.put("description", "Cria o pedido (gerando próximo PED-XXX automático no banco se AUTO), adiciona os itens e confirma/cancela na interface /pedidos.");
            function.set("parameters", parameters);

            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            tool.set("function", function);
            tools.add(tool);
            return tools;
        }

        if (isMovimentacaoEstoque) {
            tools.add(criarDeclaracao(
                    "movimentarEstoque",
                    "Registra entrada ou saída de estoque de um produto na tabela /produtos.",
                    props(
                            "produto", "Nome ou código do produto exatamente como citado na tarefa",
                            "operacao", "entrada ou saida",
                            "quantidade", "Quantidade numérica a adicionar ou retirar"),
                    List.of("produto", "operacao", "quantidade")));
            return tools;
        }

        if (isAlteracaoProduto) {
            tools.add(criarDeclaracao(
                    "atualizarProduto",
                    "Atualiza descrição e/ou preço de um produto existente na tela /produtos.",
                    props(
                            "codigo", "Código do produto no padrão PRD-XXX",
                            "descricao", "Nova descrição (ou vazio para manter)",
                            "precoUnitario", "Novo preço numérico ex: 30.00 (ou vazio para manter)"),
                    List.of("codigo")));
            return tools;
        }

        if (isCadastroProduto) {
            tools.add(criarDeclaracao(
                    "cadastrarProduto",
                    "Preenche e salva um novo produto na tela /produtos.",
                    props(
                            "codigo", "Código PRD-XXX se informado na frase, ou \"AUTO\" se não houver código",
                            "descricao", "Nome ou descrição do produto",
                            "precoUnitario", "Preço unitário numérico (ex: 1200.00)",
                            "quantidadeEstoque", "Quantidade inicial em estoque (ex: 5)"),
                    List.of("codigo", "descricao", "precoUnitario", "quantidadeEstoque")));
            return tools;
        }

        tools.add(criarDeclaracao(
                "abrirPagina",
                "Abre /produtos ou /pedidos.",
                props("caminho", "/produtos ou /pedidos"),
                List.of("caminho")));

        tools.add(criarDeclaracao(
                "clicar",
                "Clica em um botão (button).",
                props("seletor", "Seletor do botão em ELEMENTOS"),
                List.of("seletor")));

        return tools;
    }

    private ObjectNode criarDeclaracao(
            String nome,
            String descricao,
            Map<String, String> props,
            List<String> required) {

        ObjectNode properties = objectMapper.createObjectNode();

        props.forEach((chave, desc) -> properties.set(chave,
                objectMapper.createObjectNode()
                        .put("type", "string")
                        .put("description", desc)));

        ArrayNode req = objectMapper.createArrayNode();
        required.forEach(req::add);

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", req);

        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", nome);
        function.put("description", descricao);
        function.set("parameters", parameters);

        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);

        return tool;
    }
}