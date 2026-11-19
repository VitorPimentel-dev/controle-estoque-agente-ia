package br.com.realtec.controleestoque.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class ItemPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    @ManyToOne(optional = false)
    @JoinColumn(name = "produto_codigo")
    private Produto produto;

    @Column(nullable = false)
    private Integer quantidade;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoUnitarioRegistrado;

    public ItemPedido() {
    }

    public ItemPedido(Long id, Pedido pedido, Produto produto, Integer quantidade, BigDecimal precoUnitarioRegistrado) {
        this.id = id;
        this.pedido = pedido;
        this.produto = produto;
        this.quantidade = quantidade;
        this.precoUnitarioRegistrado = precoUnitarioRegistrado;
    }

    public BigDecimal getSubtotal() {
        BigDecimal precoAtual = (pedido != null && pedido.getStatus() == StatusPedido.RASCUNHO)
                ? produto.getPrecoUnitario()
                : precoUnitarioRegistrado;
        return precoAtual.multiply(BigDecimal.valueOf(quantidade));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Pedido getPedido() {
        return pedido;
    }

    public void setPedido(Pedido pedido) {
        this.pedido = pedido;
    }

    public Produto getProduto() {
        return produto;
    }

    public void setProduto(Produto produto) {
        this.produto = produto;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(Integer quantidade) {
        this.quantidade = quantidade;
    }

    public BigDecimal getPrecoUnitarioRegistrado() {
        return precoUnitarioRegistrado;
    }

    public void setPrecoUnitarioRegistrado(BigDecimal precoUnitarioRegistrado) {
        this.precoUnitarioRegistrado = precoUnitarioRegistrado;
    }
}