package br.com.realtec.controleestoque.service;

import br.com.realtec.controleestoque.entity.Produto;
import br.com.realtec.controleestoque.repository.ProdutoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProdutoService {

    public final ProdutoRepository produtoRepository;

    public ProdutoService(ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    public List<Produto> listarTodos() {
        return produtoRepository.findAllByOrderByCodigoAsc();
    }

    public List<Produto> buscarPorTermo(String termo) {
        if (termo == null || termo.isBlank()) {
            return listarTodos();
        }
        String filtro = termo.trim();
        return produtoRepository.findByCodigoContainingIgnoreCaseOrDescricaoContainingIgnoreCase(filtro, filtro);
    }

    public Produto buscarPorCodigo(String codigo) {
        return produtoRepository.findById(codigo)
                .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado com o código: " + codigo));
    }

    @Transactional
    public Produto cadastrar(Produto produto) {
        String codigoLimpo;
        if (produto.getCodigo() == null || produto.getCodigo().isBlank() || "AUTO".equalsIgnoreCase(produto.getCodigo().trim())) {
            codigoLimpo = gerarProximoCodigo();
        } else {
            codigoLimpo = produto.getCodigo().trim().toUpperCase();
        }
        produto.setCodigo(codigoLimpo);

        // Regra: Não aceitar código de produto já utilizado
        if (produtoRepository.existsById(codigoLimpo)) {
            throw new IllegalArgumentException("Já existe um produto cadastrado com o código: " + codigoLimpo);
        }

        validarDadosBasicos(produto.getDescricao(), produto.getPrecoUnitario(), produto.getQuantidadeEstoque());
        return produtoRepository.save(produto);
    }

    private String gerarProximoCodigo() {
        Pattern pattern = Pattern.compile("(\\d+)$");
        int maior = 0;
        for (Produto p : produtoRepository.findAll()) {
            if (p.getCodigo() != null) {
                Matcher m = pattern.matcher(p.getCodigo().trim());
                if (m.find()) {
                    int num = Integer.parseInt(m.group(1));
                    if (num > maior) {
                        maior = num;
                    }
                }
            }
        }
        String candidato;
        do {
            maior++;
            candidato = String.format("PRD-%03d", maior);
        } while (produtoRepository.existsById(candidato));
        return candidato;
    }

    @Transactional
    public Produto atualizar(String codigo, String novaDescricao, BigDecimal novoPreco) {
        Produto produto = buscarPorCodigo(codigo);

        if (novaDescricao == null || novaDescricao.isBlank()) {
            throw new IllegalArgumentException("A descrição do produto não pode ser vazia.");
        }
        if (novoPreco == null || novoPreco.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O preço unitário deve ser maior que zero.");
        }

        produto.setDescricao(novaDescricao.trim());
        produto.setPrecoUnitario(novoPreco);
        return produtoRepository.save(produto);
    }

    @Transactional
    public Produto ajustarEstoque(String codigo, int deltaQuantidade) {
        if (deltaQuantidade == 0) {
            throw new IllegalArgumentException("A quantidade de ajuste não pode ser zero.");
        }

        Produto produto = buscarPorCodigo(codigo);
        int novoEstoque = produto.getQuantidadeEstoque() + deltaQuantidade;

        if (novoEstoque < 0) {
            throw new IllegalArgumentException(
                    "Estoque insuficiente para saída. Atual: " + produto.getQuantidadeEstoque() +
                            ", tentativa de saída: " + Math.abs(deltaQuantidade)
            );
        }

        produto.setQuantidadeEstoque(novoEstoque);
        return produtoRepository.save(produto);
    }

    private void validarDadosBasicos(String descricao, BigDecimal preco, Integer estoque) {
        if (descricao == null || descricao.isBlank()) {
            throw new IllegalArgumentException("A descrição do produto é obrigatória.");
        }
        if (preco == null || preco.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O preço unitário deve ser maior que zero.");
        }
        if (estoque == null || estoque < 0) {
            throw new IllegalArgumentException("A quantidade inicial em estoque não pode ser negativa.");
        }
    }
}