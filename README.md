# ExcelVCS 📊

**Version Control System for Excel Files**

ExcelVCS is a Spring Boot application that brings Git-like version control capabilities to Excel spreadsheets. Track changes, manage versions, create branches, and maintain a complete history of your Excel files with ease.

## 🌟 Features

- **Version History**: Track every change made to your Excel files with detailed commit history
- **Branching**: Create and manage multiple branches for parallel development
- **Diff & Compare**: Compare different versions of Excel files to see what changed
- **REST API**: Easy-to-use API endpoints for all VCS operations
- **File Hashing**: Content-based hashing to detect actual changes
- **Commit Metadata**: Store commit messages, authors, timestamps, and more
- **Repository Management**: Organize multiple Excel projects in separate repositories

## 🚀 Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- MySQL/PostgreSQL (or any JPA-compatible database)

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/Rits396/ExcelVCS.git
   cd ExcelVCS/ExcelVCS-master
   ```

2. **Configure database**
   
   Edit `src/main/resources/application.properties`:
   ```properties
   spring.datasource.url=jdbc:mysql://localhost:3306/excelvcs
   spring.datasource.username=your_username
   spring.datasource.password=your_password
   spring.jpa.hibernate.ddl-auto=update
   ```

3. **Build the project**
   ```bash
   mvn clean install
   ```

4. **Run the application**
   ```bash
   mvn spring-boot:run
   ```

The application will start on `http://localhost:8080`

## 📖 API Documentation

### Interactive API Documentation (Swagger)

ExcelVCS comes with built-in Swagger/OpenAPI documentation for easy API exploration and testing.

**Access Swagger UI:**
```
http://localhost:8080/swagger-ui.html
```

or

```
http://localhost:8080/swagger-ui/index.html
```

**View OpenAPI Specification:**
```
http://localhost:8080/v3/api-docs
```

The Swagger UI provides:
- 📋 Complete API endpoint listing
- 🧪 Interactive request testing
- 📝 Request/response schemas
- 💡 Example values and descriptions
- 🔍 Model definitions

### Quick API Reference

### Repository Operations

#### Initialize a New Repository
```http
POST /repo/init
Content-Type: application/json

{
  "name": "Financial Reports 2024",
  "description": "Quarterly financial spreadsheets"
}
```

#### Get Repository Details
```http
GET /repo/{repoId}
```

#### List All Repositories
```http
GET /repo/list
```

### File Operations

#### Add Excel File
```http
POST /file/add
Content-Type: multipart/form-data

repoId: 1
file: [Excel file]
message: "Added Q1 report"
```

#### Commit Changes
```http
POST /file/commit
Content-Type: application/json

{
  "repoId": 1,
  "message": "Updated revenue figures",
  "author": "John Doe"
}
```

#### Get File History
```http
GET /file/history/{fileId}
```

#### Compare Versions
```http
GET /file/diff/{fileId}?version1=1&version2=2
```

### Branch Operations

#### Create Branch
```http
POST /repo/branch
Content-Type: application/json

{
  "repoId": 1,
  "branchName": "feature/q2-updates",
  "sourceCommit": "abc123"
}
```

#### List Branches
```http
GET /repo/{repoId}/branches
```

#### Checkout Branch
```http
POST /repo/checkout
Content-Type: application/json

{
  "repoId": 1,
  "branchName": "main"
}
```

### Version Control

#### View Commit History
```http
GET /repo/{repoId}/history
```

#### Revert to Previous Version
```http
POST /file/revert
Content-Type: application/json

{
  "fileId": 1,
  "commitId": "abc123"
}
```

## 🏗️ Architecture

```
ExcelVCS
├── controllers/          # REST API endpoints
│   ├── VcsController.java
│   ├── ExcelFileController.java
│   └── RepositoryController.java
├── entities/             # Domain models
│   ├── Repository.java
│   ├── Commit.java
│   ├── Branch.java
│   ├── ExcelFile.java
│   ├── ExcelWorkbook.java
│   └── Version.java
├── repositories/         # Data access layer (Spring Data JPA)
│   ├── RepositoryRepo.java
│   ├── CommitRepo.java
│   ├── BranchRepo.java
│   └── ExcelFileRepo.java
├── services/             # Business logic
│   ├── VcsService.java
│   ├── ExcelService.java
│   └── VersionService.java
└── utils/                # Helper utilities
    ├── ExcelFileReader.java
    ├── ExcelFileWriter.java
    ├── ExcelComparator.java
    └── FileHasher.java
```

## 💡 Usage Examples

### Example 1: Basic Workflow

```bash
# 1. Initialize a repository
curl -X POST http://localhost:8080/repo/init \
  -H "Content-Type: application/json" \
  -d '{"name": "Sales Data", "description": "Monthly sales reports"}'

# 2. Add an Excel file
curl -X POST http://localhost:8080/file/add \
  -F "repoId=1" \
  -F "file=@sales_jan.xlsx" \
  -F "message=Initial commit"

# 3. View history
curl http://localhost:8080/repo/1/history
```

### Example 2: Branching Workflow

```bash
# Create a new branch
curl -X POST http://localhost:8080/repo/branch \
  -H "Content-Type: application/json" \
  -d '{"repoId": 1, "branchName": "experiment"}'

# Switch to the branch
curl -X POST http://localhost:8080/repo/checkout \
  -H "Content-Type: application/json" \
  -d '{"repoId": 1, "branchName": "experiment"}'

# Make changes and commit
curl -X POST http://localhost:8080/file/commit \
  -H "Content-Type: application/json" \
  -d '{"repoId": 1, "message": "Experimental changes"}'
```

## 🛠️ Technology Stack

- **Backend**: Spring Boot 3.x
- **ORM**: Spring Data JPA / Hibernate
- **Excel Processing**: Apache POI
- **Build Tool**: Maven
- **Database**: MySQL / PostgreSQL (configurable)
- **Java Version**: 17+

## 🧪 Testing

Run the test suite:

```bash
mvn test
```

Run integration tests:

```bash
mvn verify
```

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📝 Roadmap

- [ ] Visual diff viewer for Excel changes
- [ ] Merge conflict resolution UI
- [ ] Support for Excel formulas tracking
- [ ] Collaborative editing features
- [ ] Integration with cloud storage (S3, Google Drive)
- [ ] Desktop client application
- [ ] Support for other spreadsheet formats (Google Sheets, LibreOffice)
- [ ] Advanced search and filtering
- [ ] Role-based access control
- [ ] Audit logs and analytics

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 👥 Authors

- **Rits396** - *Initial work* - [GitHub](https://github.com/Rits396)

## 🙏 Acknowledgments

- Apache POI team for excellent Excel processing capabilities
- Spring Boot community for the robust framework
- Git for inspiration on version control concepts

## 📧 Contact

For questions or support, please open an issue on GitHub or contact the maintainers.

---

**Happy Version Controlling! 🎉**
