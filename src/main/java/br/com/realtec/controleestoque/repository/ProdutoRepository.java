package br.com.realtec.controleestoque.repository;

import br.com.realtec.controleestoque.entity.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProdutoRepository extends JpaRepository<Produto,String> {

    List<Produto> findAllByOrderByCodigoAsc();

    List<Produto> findByCodigoContainingIgnoreCaseOrDescricaoContainingIgnoreCase(String codigo, String descricao);
}
