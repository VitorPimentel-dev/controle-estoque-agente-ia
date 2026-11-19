package br.com.realtec.controleestoque.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class LogExecucaoAgente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime dataHora = LocalDateTime.now();

    @Column(length = 4000)
    private String comandoRecebido;

    @Column(length = 4000)
    private String acoesRealizadas;

    @Column(length = 4000)
    private String resultadoEsperado;

    @Column(length = 4000)
    private String resultadoObservado;

    @Column(length = 4000)
    private String conclusao;

    private boolean sucesso;

    public LogExecucaoAgente(){}

    public Long getId(){
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getDataHora() {
        return dataHora;
    }

    public void setDataHora(LocalDateTime dataHora) {
        this.dataHora = dataHora;
    }

    public String getComandoRecebido() {
        return comandoRecebido;
    }

    public void setComandoRecebido(String comandoRecebido) {
        this.comandoRecebido = comandoRecebido;
    }

    public String getAcoesRealizadas() {
        return acoesRealizadas;
    }

    public void setAcoesRealizadas(String acoesRealizadas) {
        this.acoesRealizadas = acoesRealizadas;
    }

    public String getResultadoEsperado() {
        return resultadoEsperado;
    }

    public void setResultadoEsperado(String resultadoEsperado) {
        this.resultadoEsperado = resultadoEsperado;
    }

    public String getResultadoObservado() {
        return resultadoObservado;
    }

    public void setResultadoObservado(String resultadoObservado) {
        this.resultadoObservado = resultadoObservado;
    }

    public String getConclusao() {
        return conclusao;
    }

    public void setConclusao(String conclusao) {
        this.conclusao = conclusao;
    }

    public boolean isSucesso() {
        return sucesso;
    }

    public void setSucesso(boolean sucesso) {
        this.sucesso = sucesso;
    }

    public LogExecucaoAgente(Long id, LocalDateTime dataHora, String comandoRecebido, String acoesRealizadas, String resultadoEsperado, String resultadoObservado, String conclusao, boolean sucesso) {
        this.id = id;
        this.dataHora = dataHora;
        this.comandoRecebido = comandoRecebido;
        this.acoesRealizadas = acoesRealizadas;
        this.resultadoEsperado = resultadoEsperado;
        this.resultadoObservado = resultadoObservado;
        this.conclusao = conclusao;
        this.sucesso = sucesso;
    }

}
