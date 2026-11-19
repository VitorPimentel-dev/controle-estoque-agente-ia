package br.com.realtec.controleestoque.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Produto
{
    @Id
    @Column(unique = true, nullable = false)
    private String codigo;

    @Column(nullable = false)
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoUnitario;

    @Column(nullable = false)
    private Integer qntEstoque;

    public Produto(){}

    public Produto(String codigo, String descricao, BigDecimal precoUnitario, Integer qntEstoque)
    {
        this.codigo = codigo;
        this.descricao = descricao;
        this.precoUnitario = precoUnitario;
        this.qntEstoque = qntEstoque;
    }

    public String getCodigo()
    {
        return codigo;
    }

    public void setCodigo(String codigo)
    {
        this.codigo = codigo;
    }

    public String getDescricao()
    {
        return descricao;
    }

    public void setDescricao(String descricao)
    {
        this.descricao = descricao;
    }

    public BigDecimal getPrecoUnitario()
    {
        return precoUnitario;
    }

    public void setPrecoUnitario(BigDecimal precoUnitario)
    {
        this.precoUnitario = precoUnitario;
    }

    public Integer getQuantidadeEstoque()
    {
        return qntEstoque;
    }

    public void setQuantidadeEstoque(Integer qntEstoque)
    {
        this.qntEstoque = qntEstoque;
    }
}
