// Load endpoints data and render
async function loadEndpoints() {
    try {
        const response = await fetch('/endpoints/data');
        const data = await response.json();
        renderEndpoints(data);
    } catch (error) {
        console.error('Failed to load endpoints:', error);
        document.getElementById('endpoints-content').innerHTML = 
            '<div style="color: #ef5350;">Failed to load endpoints documentation.</div>';
    }
}

function renderEndpoints(data) {
    const container = document.getElementById('endpoints-content');
    
    // Group by category
    const categories = {};
    data.endpoints.forEach(endpoint => {
        if (!categories[endpoint.category]) {
            categories[endpoint.category] = [];
        }
        categories[endpoint.category].push(endpoint);
    });
    
    // Render each category
    Object.keys(categories).sort().forEach(category => {
        const section = createCategorySection(category, categories[category], data.baseUrl);
        container.appendChild(section);
    });
}

function createCategorySection(category, endpoints, baseUrl) {
    const section = document.createElement('div');
    section.className = 'category-section';
    
    const header = document.createElement('div');
    header.className = 'category-header';
    header.onclick = () => toggleCategory(header);
    
    header.innerHTML = `
        <div class="category-title">
            <span class="category-icon">▶</span>
            ${category}
        </div>
        <span style="color: #888; font-size: 0.9em;">${endpoints.length} endpoint${endpoints.length !== 1 ? 's' : ''}</span>
    `;
    
    const content = document.createElement('div');
    content.className = 'category-content';
    
    endpoints.forEach(endpoint => {
        content.appendChild(createEndpointElement(endpoint, baseUrl));
    });
    
    section.appendChild(header);
    section.appendChild(content);
    
    return section;
}

function createEndpointElement(endpoint, baseUrl) {
    const div = document.createElement('div');
    div.className = 'endpoint';
    
    let html = `
        <span class="endpoint-method">${endpoint.method}</span>
        <div class="endpoint-path">${endpoint.path}</div>
        <div class="endpoint-description">${endpoint.description || 'No description available.'}</div>
    `;
    
    if (endpoint.responseType) {
        html += `<span class="response-type">Returns: ${endpoint.responseType}</span>`;
    }
    
    if (endpoint.queryParams && endpoint.queryParams.length > 0) {
        html += `
            <div class="params-section">
                <div class="params-title">Query Parameters:</div>
        `;
        
        endpoint.queryParams.forEach(param => {
            const required = param.required ? 
                `<span class="param-required">Required</span>` : 
                `<span class="param-optional">Optional</span>`;
            
            const defaultValue = param.defaultValue ? 
                `<span class="param-default">(default: ${param.defaultValue})</span>` : '';
            
            html += `
                <div class="param">
                    <span class="param-name">${param.name}</span>
                    <span class="param-type">${param.type}</span>
                    ${required}
                    ${defaultValue}
                    <div class="param-description">${param.description || 'No description.'}</div>
                </div>
            `;
        });
        
        html += `</div>`;
    } else {
        html += `
            <div class="params-section">
                <div class="no-params">No query parameters</div>
            </div>
        `;
    }
    
    if (endpoint.examples && endpoint.examples.length > 0) {
        html += `
            <div class="example">
                <div class="example-label">Examples:</div>
        `;
        
        endpoint.examples.forEach(example => {
    const fullUrl = example.startsWith('http') ? example : `${baseUrl}${example}`;
    html += `<div class="example-code" onclick="copyToClipboard('${fullUrl}')" title="Click to copy">${fullUrl}</div>`;
        });
        
        html += `</div>`;
    }
    
    div.innerHTML = html;
    return div;
}

function toggleCategory(header) {
    const content = header.nextElementSibling;
    const isExpanded = content.classList.contains('expanded');
    
    if (isExpanded) {
        content.classList.remove('expanded');
        header.classList.remove('active');
    } else {
        content.classList.add('expanded');
        header.classList.add('active');
    }
}

// Auto-expand first category by default
function autoExpandFirst() {
    const firstHeader = document.querySelector('.category-header');
    if (firstHeader) {
        toggleCategory(firstHeader);
    }
}

function copyToClipboard(text) {
    navigator.clipboard.writeText(text).then(() => {
        // Visual feedback could be added here
    }).catch(err => {
        console.error('Failed to copy:', err);
    });
}

// Load and render on page load
window.addEventListener('DOMContentLoaded', () => {
    loadEndpoints().then(() => {
        // Auto-expand first category after a short delay to ensure rendering is complete
        setTimeout(autoExpandFirst, 100);
    });
});

