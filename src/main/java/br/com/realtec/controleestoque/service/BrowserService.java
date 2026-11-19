package br.com.realtec.controleestoque.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BrowserService {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // Playwright nao e thread-safe: tudo roda nesta thread unica
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Playwright playwright;
    private Browser browser;
    private Page page;

    private <T> T run(Callable<T> tarefa) {
        Future<T> f = exec.submit(tarefa);
        try {
            return f.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new RuntimeException("tempo esgotado ao operar o navegador");
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrompido", e);
        }
    }

    private Page page() {
        if (playwright == null) playwright = Playwright.create();
        if (browser == null || !browser.isConnected()) {
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            page = null;
        }
        if (page == null || page.isClosed()) {
            page = browser.newPage();
            page.setDefaultTimeout(5000);
            page.setDefaultNavigationTimeout(15000);
            page.navigate(baseUrl + "/produtos");
            page.waitForLoadState();
        }
        return page;
    }

    public String abrir(String caminho) {
        if (caminho == null || caminho.isBlank())
            throw new RuntimeException("caminho nao informado");
        if (caminho.contains("/agente"))
            throw new RuntimeException("a pagina do agente nao pode ser usada pelo agente");
        return run(() -> {
            String destino = "/".equals(caminho) ? "/produtos" : caminho;
            // bloqueia navegacao para fora do sistema
            if (destino.startsWith("http") && !destino.startsWith(baseUrl))
                throw new RuntimeException("url fora do sistema nao permitida");
            page().navigate(destino.startsWith("http") ? destino : baseUrl + destino);
            page().waitForLoadState();
            return tela();
        });
    }

    public String clicar(String seletor) {
        return run(() -> {
            page().click(seletor);
            page().waitForLoadState();
            return tela();
        });
    }

    public String preencher(String seletor, String valor) {
        return run(() -> {
            String valorFinal = normalizarValor(seletor, valor);
            String tag = (String) page().locator(seletor).first().evaluate("e => e.tagName.toLowerCase()");
            if ("select".equals(tag)) {
                page().selectOption(seletor, valorFinal);
                return "opcao selecionada";
            }
            page().fill(seletor, valorFinal);
            return "campo preenchido";
        });
    }

    public String selecionar(String seletor, String valor) {
        return run(() -> {
            String valorFinal = normalizarValor(seletor, valor);
            String tag = (String) page().locator(seletor).first().evaluate("e => e.tagName.toLowerCase()");
            if (!"select".equals(tag)) {
                page().fill(seletor, valorFinal);
                return "campo preenchido";
            }
            page().selectOption(seletor, valorFinal);
            return "opcao selecionada";
        });
    }

    public String cadastrarProdutoNaTela(String codigoInformado, String descricao, String precoUnitario, String quantidadeEstoque) {
        return run(() -> {
            if (!page().url().contains("/produtos")) {
                page().navigate(baseUrl + "/produtos");
                page().waitForLoadState();
            }

            String codFinal = normalizarCodigoProduto(codigoInformado);
            String descFinal = descricao == null ? "" : descricao.trim();
            String precoFinal = normalizarPreco(precoUnitario);
            String estFinal = quantidadeEstoque == null ? "0" : quantidadeEstoque.replaceAll("[^\\d]", "");
            if (estFinal.isEmpty()) estFinal = "0";

            page().fill("#codigo", codFinal);
            page().fill("#descricao", descFinal);
            page().fill("#precoUnitario", precoFinal);
            page().fill("#quantidadeEstoque", estFinal);
            page().click("button:has-text('Salvar')");
            page().waitForLoadState();

            String corpo = page().innerText("body");
            String erro = resumirErroTela(corpo);
            if (!erro.isEmpty()) {
                throw new RuntimeException(erro);
            }
            return tela();
        });
    }

    public String movimentarEstoqueNaTela(String produtoBusca, String operacao, String quantidadeStr) {
        return run(() -> {
            if (!page().url().contains("/produtos")) {
                page().navigate(baseUrl + "/produtos");
                page().waitForLoadState();
            }

            String termoNorm = normalizarTexto(produtoBusca);
            // busca vazia casaria com a primeira linha da tabela
            if (termoNorm.isEmpty()) {
                throw new RuntimeException("Produto não informado para movimentação de estoque.");
            }
            String termoSingular = termoNorm.endsWith("es") && termoNorm.length() > 4
                    ? termoNorm.substring(0, termoNorm.length() - 2)
                    : (termoNorm.endsWith("s") && termoNorm.length() > 3 ? termoNorm.substring(0, termoNorm.length() - 1) : termoNorm);

            String qtd = quantidadeStr == null ? "" : quantidadeStr.replaceAll("[^\\d]", "");
            if (qtd.isEmpty() || Integer.parseInt(qtd) <= 0) {
                throw new RuntimeException("Quantidade inválida para movimentação de estoque.");
            }

            boolean isSaida = operacao != null && normalizarTexto(operacao).matches(".*(said|diminu|baix|remov|retir|subtr).*");

            @SuppressWarnings("unchecked")
            List<String> encontrado = (List<String>) page().evaluate("""
                ([busca, buscaSing]) => {
                    const semAcento = (s) => s.normalize('NFD').replace(/[\\u0300-\\u036f]/g, '').toLowerCase().trim();
                    for (const tr of document.querySelectorAll('table tbody tr')) {
                        const tds = tr.querySelectorAll('td');
                        if (tds.length >= 4) {
                            const cod = tds[0].innerText.trim();
                            const desc = tds[1].innerText.trim();
                            const saldo = tds[3].innerText.trim();
                            const codN = semAcento(cod);
                            const descN = semAcento(desc);
                            if (codN === busca || codN === 'prd-' + busca
                                || descN.includes(busca) || (buscaSing && descN.includes(buscaSing))) {
                                return [cod, desc, saldo];
                            }
                        }
                    }
                    return null;
                }
                """, List.of(termoNorm, termoSingular));

            if (encontrado == null || encontrado.isEmpty()) {
                throw new RuntimeException("Produto '" + produtoBusca + "' não encontrado na tabela /produtos.");
            }

            String codigoExato = encontrado.get(0);
            String saldoAntes = encontrado.get(2);
            Locator linha = page().locator("table tbody tr").filter(new Locator.FilterOptions().setHasText(exato(codigoExato))).first();

            String actionEsperada = isSaida
                    ? "/produtos/" + codigoExato + "/saida"
                    : "/produtos/" + codigoExato + "/entrada";

            Locator formDireto = linha.locator("form[action='" + actionEsperada + "']");
            if (formDireto.count() > 0) {
                formDireto.locator("input:not([type='hidden'])").first().fill(qtd);
                formDireto.locator("button[type='submit']").first().click();
                page().waitForLoadState();
            } else {
                Locator btn = isSaida
                        ? linha.locator("button:has-text('Saída'), button:has-text('Saida'), button:has-text('-')").first()
                        : linha.locator("button:has-text('Entrada'), button:has-text('+')").first();
                if (btn.count() == 0) {
                    throw new RuntimeException("Botão de " + (isSaida ? "Saída" : "Entrada") + " não encontrado para " + codigoExato);
                }

                Locator formDoBotao = btn.locator("xpath=ancestor::form[1]");
                Locator inputAlvo = formDoBotao.count() > 0
                        ? formDoBotao.locator("input:not([type='hidden'])").first()
                        : (isSaida
                           ? linha.locator("input:not([type='hidden'])").last()
                           : linha.locator("input:not([type='hidden'])").first());

                if (inputAlvo.count() == 0) {
                    throw new RuntimeException("Campo de quantidade não encontrado na linha do produto " + codigoExato);
                }
                inputAlvo.fill(qtd);
                btn.click();
                page().waitForLoadState();
            }

            String corpo = page().innerText("body");
            String erro = resumirErroTela(corpo);
            if (!erro.isEmpty()) {
                throw new RuntimeException(erro);
            }

            String saldoDepois = page().locator("table tbody tr")
                    .filter(new Locator.FilterOptions().setHasText(exato(codigoExato)))
                    .first()
                    .locator("td")
                    .nth(3)
                    .innerText()
                    .trim();

            int antes = Integer.parseInt(saldoAntes.replaceAll("[^\\d-]", ""));
            int depois = Integer.parseInt(saldoDepois.replaceAll("[^\\d-]", ""));
            int esperado = isSaida ? (antes - Integer.parseInt(qtd)) : (antes + Integer.parseInt(qtd));

            if (depois != esperado) {
                throw new RuntimeException("Divergência no saldo de " + codigoExato + ": esperado " + esperado + ", mas ficou " + depois + ".");
            }

            return "Movimentação realizada com sucesso para " + codigoExato + " (" + saldoAntes + " -> " + saldoDepois + ").\n\n" + tela();
        });
    }

    public String atualizarProdutoNaTela(String codigoInformado, String novaDescricao, String novoPreco) {
        return run(() -> {
            if (!page().url().contains("/produtos")) {
                page().navigate(baseUrl + "/produtos");
                page().waitForLoadState();
            }

            String codNorm = normalizarCodigoProduto(codigoInformado);

            @SuppressWarnings("unchecked")
            List<String> atuais = (List<String>) page().evaluate("""
                (cod) => {
                    const alvo = cod.trim().toUpperCase();
                    const alvoSemPrefixo = alvo.replace(/^PRD-/, '');
                    for (const tr of document.querySelectorAll('table tbody tr')) {
                        const tds = tr.querySelectorAll('td');
                        if (tds.length >= 3) {
                            const codTab = tds[0].innerText.trim().toUpperCase();
                            if (codTab === alvo || codTab === alvoSemPrefixo || codTab.replace(/^PRD-/, '') === alvoSemPrefixo) {
                                const desc = tds[1].innerText.trim();
                                const preco = tds[2].innerText.replace('R$', '').replace(/\\./g, '').replace(',', '.').trim();
                                return [tds[0].innerText.trim(), desc, preco];
                            }
                        }
                    }
                    return null;
                }
                """, codNorm);

            if (atuais == null || atuais.size() < 3) {
                throw new RuntimeException("Produto não encontrado na tabela: " + codNorm);
            }

            String codigoExato = atuais.get(0);
            String descFinal = (novaDescricao != null && !novaDescricao.isBlank()) ? novaDescricao.trim() : atuais.get(1);
            String precoFinal = (novoPreco != null && !novoPreco.isBlank())
                    ? normalizarPreco(novoPreco)
                    : atuais.get(2);

            String action = "/produtos/" + codigoExato + "/atualizar";
            Locator form = page().locator("form[action='" + action + "']");

            if (form.count() > 0) {
                if (!form.first().isVisible()) {
                    Locator btnEditar = page().locator("tr:has-text('" + codigoExato + "') button:has-text('Editar'), button[data-bs-target*='" + codigoExato + "']");
                    if (btnEditar.count() > 0) {
                        btnEditar.first().click();
                    }
                }
                form.locator("input[name='descricao']").fill(descFinal);
                form.locator("input[name='precoUnitario']").fill(precoFinal);
                form.locator("button[type='submit']").first().click();
                page().waitForLoadState();
                return tela();
            }

            page().evaluate("""
                ([actionUrl, desc, preco]) => {
                    const f = document.createElement('form');
                    f.method = 'POST';
                    f.action = actionUrl;
                    const i1 = document.createElement('input');
                    i1.name = 'descricao';
                    i1.value = desc;
                    const i2 = document.createElement('input');
                    i2.name = 'precoUnitario';
                    i2.value = preco;
                    f.appendChild(i1);
                    f.appendChild(i2);
                    document.body.appendChild(f);
                    f.submit();
                }
                """, List.of(action, descFinal, precoFinal));
            page().waitForLoadState();
            return tela();
        });
    }

    public String processarPedidoNaTela(String identificadorInformado, List<Map<String, Object>> itens, String acaoFinal, boolean limparRascunhoExistente) {
        return run(() -> {
            if (!page().url().contains("/pedidos")) {
                page().navigate(baseUrl + "/pedidos");
                page().waitForLoadState();
            }

            boolean isAuto = identificadorInformado == null
                    || identificadorInformado.isBlank()
                    || "AUTO".equalsIgnoreCase(identificadorInformado.trim());

            String idPedido = isAuto ? "AUTO" : normalizarCodigoPedido(identificadorInformado);
            String acaoNorm = acaoFinal == null ? "" : acaoFinal.trim().toLowerCase();

            // Fluxo direto de cancelamento de pedido existente
            if (acaoNorm.contains("cancel")) {
                if ("AUTO".equals(idPedido)) {
                    throw new RuntimeException("Informe o código do pedido que deseja cancelar (ex: PED-005).");
                }
                Locator formCancelar = page().locator("form[action='/pedidos/" + idPedido + "/cancelar']");
                if (formCancelar.count() == 0) {
                    String corpo = page().innerText("body");
                    if (corpo.contains(idPedido)) {
                        throw new RuntimeException("O pedido " + idPedido + " não possui botão de cancelar disponível.");
                    }
                    throw new RuntimeException("Pedido " + idPedido + " não encontrado na tela /pedidos.");
                }
                formCancelar.locator("button[type='submit']").first().click();
                page().waitForLoadState();

                String corpoAposCancelar = page().innerText("body");
                String erro = resumirErroTela(corpoAposCancelar);
                if (!erro.isEmpty()) {
                    throw new RuntimeException(erro);
                }
                return tela();
            }

            // 1. Cria rascunho se for AUTO, novo pedido (valida duplicidade) ou se ainda nao existir form de itens
            if ("AUTO".equals(idPedido)
                    || limparRascunhoExistente
                    || page().locator("form[action='/pedidos/" + idPedido + "/itens/adicionar']").count() == 0) {
                Locator formCriar = page().locator("form[action='/pedidos/criar']");
                if (formCriar.count() == 0) {
                    throw new RuntimeException("Formulário de criação de pedido (/pedidos/criar) não encontrado na tela.");
                }
                formCriar.locator("input[name='identificador']").fill(idPedido);
                formCriar.locator("button[type='submit']").first().click();
                page().waitForLoadState();

                String corpo = page().innerText("body");
                String erroCriar = resumirErroTela(corpo);
                if (!erroCriar.isEmpty()) {
                    throw new RuntimeException(erroCriar);
                }

                if ("AUTO".equals(idPedido)) {
                    Matcher m = Pattern.compile("Rascunho de pedido\\s+(PED-\\d+)\\s+criado", Pattern.CASE_INSENSITIVE).matcher(corpo);
                    if (m.find()) {
                        idPedido = m.group(1).toUpperCase();
                    } else {
                        throw new RuntimeException("Não foi possível identificar o código gerado pelo sistema.");
                    }
                } else if (page().locator("form[action='/pedidos/" + idPedido + "/itens/adicionar']").count() == 0) {
                    throw new RuntimeException("Não foi possível abrir o rascunho " + idPedido + ".");
                }
            }

            String actionAdd = "/pedidos/" + idPedido + "/itens/adicionar";

            // 2. Adiciona cada item solicitado
            if (itens != null) {
                for (Map<String, Object> item : itens) {
                    String prodBusca = String.valueOf(item.getOrDefault("produto", "")).trim();
                    String qtd = String.valueOf(item.getOrDefault("quantidade", "1")).replaceAll("[^\\d]", "");
                    if (qtd.isEmpty()) qtd = "1";

                    Locator formAdd = page().locator("form[action='" + actionAdd + "']");
                    if (formAdd.count() == 0) {
                        throw new RuntimeException("Formulário de itens não encontrado para o pedido " + idPedido);
                    }

                    Locator selectProd = formAdd.locator("select[name='codigoProduto']");
                    String optionValue = encontrarOptionProduto(selectProd, prodBusca);
                    if (optionValue == null) {
                        throw new RuntimeException("Produto '" + prodBusca + "' não encontrado na lista de produtos disponíveis.");
                    }

                    // remove linha existente do mesmo produto (limite evita loop infinito)
                    String prefixoRemover = "/pedidos/" + idPedido + "/itens/";
                    Locator linhaExistente = page().locator("tr:has(form[action^='" + prefixoRemover + "'][action$='/remover'])")
                            .filter(new Locator.FilterOptions().setHasText(exato(optionValue)));
                    int tentativas = 0;
                    while (linhaExistente.count() > 0 && tentativas++ < 20) {
                        linhaExistente.first().locator("form[action$='/remover'] button[type='submit']").first().click();
                        page().waitForLoadState();
                    }

                    selectProd.selectOption(optionValue);
                    formAdd.locator("input[name='quantidade']").fill(qtd);
                    formAdd.locator("button[type='submit']").first().click();
                    page().waitForLoadState();

                    String corpoAposItem = page().innerText("body");
                    if (corpoAposItem.toLowerCase().contains("insuficiente") || corpoAposItem.toLowerCase().contains("erro")) {
                        String erro = resumirErroTela(corpoAposItem);
                        if (!erro.isEmpty()) {
                            throw new RuntimeException(erro);
                        }
                    }
                }
            }

            // 3. Executa confirmacao se solicitado
            if (acaoNorm.contains("confirm")) {
                Locator formConfirmar = page().locator("form[action='/pedidos/" + idPedido + "/confirmar']");
                if (formConfirmar.count() == 0) {
                    throw new RuntimeException("Botão de confirmar não encontrado para o pedido " + idPedido);
                }
                formConfirmar.locator("button[type='submit']").first().click();
                page().waitForLoadState();

                String corpoAposConfirmar = page().innerText("body");
                String erro = resumirErroTela(corpoAposConfirmar);
                if (!erro.isEmpty()) {
                    throw new RuntimeException(erro);
                }
            }

            return tela();
        });
    }

    private String encontrarOptionProduto(Locator selectProd, String termo) {
        @SuppressWarnings("unchecked")
        List<List<String>> opcoes = (List<List<String>>) selectProd.evaluate("""
            (sel) => [...sel.options]
                .filter(o => o.value && o.value.trim() !== '')
                .map(o => [o.value, o.textContent.trim()])
            """);

        if (opcoes == null || opcoes.isEmpty()) return null;

        String busca = normalizarTexto(termo);
        if (busca.isEmpty()) return null;
        String buscaSingular = busca.endsWith("es") && busca.length() > 4
                ? busca.substring(0, busca.length() - 2)
                : (busca.endsWith("s") && busca.length() > 3 ? busca.substring(0, busca.length() - 1) : busca);

        for (List<String> op : opcoes) {
            String val = normalizarTexto(op.get(0));
            if (val.equals(busca) || val.equals("prd-" + busca)) {
                return op.get(0);
            }
        }

        for (List<String> op : opcoes) {
            String txt = normalizarTexto(op.get(1));
            if (txt.contains(busca) || (!buscaSingular.isEmpty() && txt.contains(buscaSingular))) {
                return op.get(0);
            }
        }

        return null;
    }

    // casa o codigo inteiro (PRD-001 nao casa com PRD-0010)
    private Pattern exato(String s) {
        // sem Pattern.quote: \Q..\E nao existe em regex JS (Playwright)
        return Pattern.compile("\\b" + s.replaceAll("([^A-Za-z0-9])", "\\\\$1") + "\\b");
    }

    private String normalizarTexto(String s) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return semAcento.toLowerCase().trim();
    }

    // "1.200,50" -> "1200.50"; "1200.00" fica igual
    private String normalizarPreco(String v) {
        if (v == null) return "";
        String s = v.replace("R$", "").replaceAll("\\s", "");
        if (s.contains(",")) s = s.replace(".", "").replace(",", ".");
        return s;
    }

    private String resumirErroTela(String corpo) {
        for (String linha : corpo.split("\\R")) {
            String l = linha.trim();
            String low = l.toLowerCase();
            if (low.contains("insuficiente")
                    || low.contains("já existe")
                    || low.contains("não encontrado")
                    || low.contains("não é possível")
                    || low.contains("inválid")
                    || low.contains("obrigatóri")
                    || low.contains("maior que zero")
                    || low.contains("erro")) {
                return l;
            }
        }
        return "";
    }

    private String normalizarCodigoProduto(String valor) {
        if (valor == null || valor.isBlank()) return "AUTO";
        String cod = valor.trim().toUpperCase();
        if ("AUTO".equals(cod)) return "AUTO";
        if (cod.matches("^PR-\\d+$")) {
            cod = cod.replaceFirst("^PR-", "PRD-");
        } else if (cod.matches("^\\d+$")) {
            cod = "PRD-" + (cod.length() < 3 ? String.format("%03d", Integer.parseInt(cod)) : cod);
        }
        return cod;
    }

    private String normalizarCodigoPedido(String valor) {
        if (valor == null || valor.isBlank()) return "AUTO";
        String cod = valor.trim().toUpperCase();
        if ("AUTO".equals(cod)) return "AUTO";
        if (cod.matches("^\\d+$")) {
            return "PED-" + (cod.length() < 3 ? String.format("%03d", Integer.parseInt(cod)) : cod);
        }
        if (cod.matches("^PE-\\d+$")) {
            cod = cod.replaceFirst("^PE-", "PED-");
        }
        return cod;
    }

    private String normalizarValor(String seletor, String valor) {
        if (valor == null) return "";
        if (seletor == null) return valor;
        if (seletor.contains("codigo")) {
            return normalizarCodigoProduto(valor);
        }
        if (seletor.contains("identificador")) {
            return normalizarCodigoPedido(valor);
        }
        return valor;
    }

    public String lerTela() {
        return run(this::tela);
    }

    private String tela() {
        Page p = page();
        String texto = p.innerText("body");
        if (texto.length() > 1400) texto = texto.substring(0, 1400) + "...";

        String elementos = (String) p.evaluate("""
            () => [...document.querySelectorAll('a,button,input,select,textarea')]
              .filter(e => e.offsetParent !== null
                           && !(e.getAttribute('href') || '').includes('/agente'))
              .map(e => {
                const tag = e.tagName.toLowerCase();
                const txt = (e.innerText || e.value || e.placeholder || '').trim().slice(0, 40);
                let sel;
                if (e.id) sel = '#' + e.id;
                else if (e.name) sel = tag + '[name="' + e.name + '"]';
                else if (tag === 'a') sel = 'a[href="' + e.getAttribute('href') + '"]';
                else sel = tag + ':has-text("' + txt + '")';
                return tag + ' | ' + (e.type || '') + ' | ' + sel + ' | ' + txt;
              }).join('\\n')
            """);

        String resultado = "URL: " + p.url() + "\n\nELEMENTOS (tag | tipo | seletor | texto):\n" + elementos + "\n\nTEXTO:\n" + texto;
        System.out.println("--- TELA ---\n" + resultado + "\n------------");
        return resultado;
    }

    @PreDestroy
    void fechar() {
        try {
            // fecha na mesma thread que criou o Playwright
            exec.submit(() -> {
                if (browser != null) browser.close();
                if (playwright != null) playwright.close();
                return null;
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
    }
}