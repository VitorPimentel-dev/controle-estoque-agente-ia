package br.com.realtec.controleestoque.service;

import br.com.realtec.controleestoque.entity.ItemPedido;
import br.com.realtec.controleestoque.entity.Pedido;
import br.com.realtec.controleestoque.entity.Produto;
import br.com.realtec.controleestoque.entity.StatusPedido;
import br.com.realtec.controleestoque.repository.PedidoRepository;
import br.com.realtec.controleestoque.repository.ProdutoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PedidoService {

    private final PedidoRepository pedidoRepository;
    private final ProdutoRepository produtoRepository;

    public PedidoService(PedidoRepository pedidoRepository, ProdutoRepository produtoRepository) {
        this.pedidoRepository = pedidoRepository;
        this.produtoRepository = produtoRepository;
    }

    public List<Pedido> listarTodos() {
        return pedidoRepository.findAllByOrderByDataCriacaoDesc();
    }

    public Pedido buscarPorIdentificador(String identificador) {
        return pedidoRepository.findById(identificador)
                .orElseThrow(() -> new IllegalArgumentException("Pedido não encontrado com o identificador: " + identificador));
    }

    @Transactional
    public Pedido criarRascunho(String identificador) {
        String idLimpo;
        if (identificador == null || identificador.isBlank() || "AUTO".equalsIgnoreCase(identificador.trim())) {
            idLimpo = gerarProximoIdentificador();
        } else {
            idLimpo = identificador.trim().toUpperCase();
        }

        if (pedidoRepository.existsById(idLimpo)) {
            throw new IllegalArgumentException("Já existe um pedido com o identificador: " + idLimpo);
        }

        Pedido pedido = new Pedido();
        pedido.setIdentificador(idLimpo);
        pedido.setStatus(StatusPedido.RASCUNHO);
        return pedidoRepository.save(pedido);
    }

    private String gerarProximoIdentificador() {
        Pattern pattern = Pattern.compile("(\\d+)$");
        int maior = 0;
        for (Pedido p : pedidoRepository.findAll()) {
            if (p.getIdentificador() != null) {
                Matcher m = pattern.matcher(p.getIdentificador().trim());
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
            candidato = String.format("PED-%03d", maior);
        } while (pedidoRepository.existsById(candidato));
        return candidato;
    }

    @Transactional
    public Pedido adicionarItem(String identificadorPedido, String codigoProduto, int quantidade) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade do item deve ser maior que zero.");
        }

        Pedido pedido = buscarPorIdentificador(identificadorPedido);
        if (pedido.getStatus() != StatusPedido.RASCUNHO) {
            throw new IllegalArgumentException("Só é possível adicionar itens em pedidos com status RASCUNHO.");
        }

        Produto produto = produtoRepository.findById(codigoProduto)
                .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado: " + codigoProduto));

        Optional<ItemPedido> itemExistente = pedido.getItens().stream()
                .filter(i -> i.getProduto().getCodigo().equals(codigoProduto))
                .findFirst();

        if (itemExistente.isPresent()) {
            ItemPedido item = itemExistente.get();
            item.setQuantidade(item.getQuantidade() + quantidade);
        } else {
            ItemPedido novoItem = new ItemPedido();
            novoItem.setPedido(pedido);
            novoItem.setProduto(produto);
            novoItem.setQuantidade(quantidade);
            novoItem.setPrecoUnitarioRegistrado(produto.getPrecoUnitario());
            pedido.getItens().add(novoItem);
        }

        pedido.recalcularTotal();
        return pedidoRepository.save(pedido);
    }

    @Transactional
    public Pedido removerItem(String identificadorPedido, Long itemId) {
        Pedido pedido = buscarPorIdentificador(identificadorPedido);
        if (pedido.getStatus() != StatusPedido.RASCUNHO) {
            throw new IllegalArgumentException("Só é possível remover itens de pedidos com status RASCUNHO.");
        }

        boolean removido = pedido.getItens().removeIf(item -> itemId.equals(item.getId()));
        if (!removido) {
            throw new IllegalArgumentException("Item não encontrado neste pedido.");
        }

        pedido.recalcularTotal();
        return pedidoRepository.save(pedido);
    }

    @Transactional
    public Pedido confirmarPedido(String identificadorPedido) {
        Pedido pedido = buscarPorIdentificador(identificadorPedido);

        if (pedido.getStatus() != StatusPedido.RASCUNHO) {
            throw new IllegalArgumentException("Apenas pedidos em RASCUNHO podem ser confirmados.");
        }
        if (pedido.getItens().isEmpty()) {
            throw new IllegalArgumentException("Não é possível confirmar um pedido sem itens.");
        }

        for (ItemPedido item : pedido.getItens()) {
            Produto produto = item.getProduto();
            if (produto.getQuantidadeEstoque() < item.getQuantidade()) {
                throw new IllegalArgumentException(
                        "Estoque insuficiente para o produto '" + produto.getDescricao() +
                                "'. Disponível: " + produto.getQuantidadeEstoque() +
                                ", solicitado: " + item.getQuantidade()
                );
            }
        }

        for (ItemPedido item : pedido.getItens()) {
            Produto produto = item.getProduto();
            produto.setQuantidadeEstoque(produto.getQuantidadeEstoque() - item.getQuantidade());
            produtoRepository.save(produto);
        }

        pedido.setStatus(StatusPedido.CONFIRMADO);
        return pedidoRepository.save(pedido);
    }

    @Transactional
    public Pedido cancelarPedido(String identificadorPedido) {
        Pedido pedido = buscarPorIdentificador(identificadorPedido);

        if (pedido.getStatus() == StatusPedido.CANCELADO) {
            throw new IllegalArgumentException("Este pedido já está cancelado.");
        }

        if (pedido.getStatus() == StatusPedido.CONFIRMADO) {
            for (ItemPedido item : pedido.getItens()) {
                Produto produto = item.getProduto();
                produto.setQuantidadeEstoque(produto.getQuantidadeEstoque() + item.getQuantidade());
                produtoRepository.save(produto);
            }
        }

        pedido.setStatus(StatusPedido.CANCELADO);
        return pedidoRepository.save(pedido);
    }
}