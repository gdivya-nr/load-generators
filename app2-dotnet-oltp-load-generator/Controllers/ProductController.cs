using LoadGen.Oltp.Services;
using Microsoft.AspNetCore.Mvc;
using NewRelic.Api.Agent;
using NR = NewRelic.Api.Agent.NewRelic;

namespace LoadGen.Oltp.Controllers;

[ApiController]
[Route("api/products")]
public class ProductController : ControllerBase
{
    private readonly ProductService _productService;
    private readonly ILogger<ProductController> _logger;

    public ProductController(ProductService productService, ILogger<ProductController> logger)
    {
        _productService = productService;
        _logger = logger;
    }

    [HttpGet("{productId}")]
    public IActionResult GetProductDetails(long productId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("productId", productId);
            _productService.GetProductDetails(productId);
            return Ok(new { productId, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error getting product details");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpPut("{productId}/price")]
    public IActionResult UpdatePrice(long productId)
    {
        try
        {
            NR.GetAgent().CurrentTransaction.AddCustomAttribute("productId", productId);
            _productService.UpdatePrice(productId);
            return Ok(new { productId, updated = true, status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error updating product price");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }

    [HttpGet("search")]
    public IActionResult SearchByCategory()
    {
        try
        {
            _productService.SearchByCategory();
            return Ok(new { status = "SUCCESS" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error searching products");
            NR.NoticeError(ex);
            return StatusCode(500, new { error = ex.Message });
        }
    }
}
