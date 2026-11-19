# Sistema de Controle de Estoque & Pedidos com Agente IA (Realtec)

Aplicação web desenvolvida em **Spring Boot 3.5.0** integrada a um **Agente de Inteligência Artificial local (Ollama + `qwen2.5:1.5b`)** que opera diretamente a interface web via **Playwright (Chromium)** para executar cadastros, movimentações de estoque, criação/alteração/cancelamento de pedidos e registrar automaticamente o histórico completo de execuções.

---

## Vídeo de Demonstração

- **Link do vídeo (3 a 5 min):** [Assistir à demonstração no Google Drive](https://drive.google.com/file/d/1whQoytTpeWAB_FtxsnW4d7bT_8uhd9fh/view?usp=sharing)
- **URL direta:** `https://drive.google.com/file/d/1whQoytTpeWAB_FtxsnW4d7bT_8uhd9fh/view?usp=sharing`
- **Operações demonstradas:** Cadastro de produtos (código explícito e automático), atualização de preço/descrição, movimentação de estoque (entrada e saída), criação/edição/confirmação de pedidos, cancelamento com estorno de saldo e visualização do histórico de execuções do Agente IA.

---

## Pré-requisitos

- **Java JDK 17** ou superior (`java -version`)
- **Apache Maven 3.8+** (`mvn -version`) ou o wrapper `./mvnw` incluso no projeto
- **Ollama** instalado e em execução (`ollama --version`)
- Modelo local **`qwen2.5:1.5b`** baixado no Ollama
- **Chromium** (baixado automaticamente pelo Playwright na primeira execução)

---

## Configuração e Execução

### 1. Configurar e iniciar o Ollama
Baixe o modelo utilizado pelo agente e garanta que o serviço local esteja ativo na porta padrão (`11434`):

```bash
ollama pull qwen2.5:1.5b
ollama serve